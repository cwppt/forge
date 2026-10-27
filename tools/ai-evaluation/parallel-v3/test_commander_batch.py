import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import CommanderBatch as runner
from test_valid_commander_cohort import fixture


def valid_fixture(directory, seed=100):
    fixture(directory, seed, 1)
    roles = [dict(gameId=1, gameIndex=0, runSeed=seed, eventIndex=i, type=kind, rawTurn=1)
             for i, kind in enumerate(['START','TURN','SNAPSHOT','END'])]
    (directory / f'commander-seed{seed}.roles.jsonl').write_text(''.join(json.dumps(e)+'\n' for e in roles))


class BatchTests(unittest.TestCase):
    def setUp(self):
        snap=patch.object(runner,'process_snapshot',return_value={})
        snap.start();self.addCleanup(snap.stop)

    def test_nonzero_exit_excludes_otherwise_valid_game(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); valid_fixture(directory)
            self.assertTrue(runner.classify(directory, 100, 0)[0]['valid'])
            result = runner.classify(directory, 100, 7)[0]
            self.assertFalse(result['valid']); self.assertEqual(result['processExitCode'], 7)

    def test_provider_attempt_excluded(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); valid_fixture(directory)
            (directory / 'commander-seed100.metrics.csv').write_text(
                'decisionId,gameId,gameIndex,runSeed,decisionType,providerAttempted\nx,1,0,100,MULLIGAN_KEEP,true\n')
            self.assertFalse(runner.classify(directory, 100, 0)[0]['valid'])

    def test_empty_launch_is_recorded_as_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            result = runner.classify(Path(temp), 100, 1)
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0]['simulationStatus'], 'PROCESS_FAILED')
            self.assertFalse(result[0]['valid'])

    def test_input_hash_change_blocks_resume(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp) / 'asset'; p.write_text('one')
            request = dict(assets=[dict(path=str(p), sha256=runner.sha(p))], ai=dict(externalAIEnabled=False))
            runner.verify(request)
            p.write_text('two')
            with self.assertRaisesRegex(RuntimeError, 'Input changed'): runner.verify(request)

    def test_final_gate_rejects_wrong_counts(self):
        with tempfile.TemporaryDirectory() as temp:
            batch = Path(temp); (batch / 'R2').mkdir(); (batch / 'R2/attempts.jsonl').write_text('')
            with patch.object(runner, 'verify'):
                with self.assertRaisesRegex(RuntimeError, 'Wrong valid count'):
                    runner.final_gate(batch, dict(target=1))

    def test_publication_dry_run_never_mutates_git(self):
        with tempfile.TemporaryDirectory() as temp:
            batch = Path(temp); (batch / 'publication-payload').mkdir()
            (batch / 'publication-payload/report.json').write_text('{}')
            runner.write(batch / 'request.json', dict(seed=999))
            runner.write(batch / 'payload-hashes.json', runner.inventory(batch / 'publication-payload'))
            calls = []
            def fake_git(repo, *args):
                calls.append(args)
                return 'main' if args[0] == 'branch' else 'https://github.com/cwppt/MTG-deck-lists.git' if args[0] == 'remote' else ''
            with patch.object(runner, 'final_gate'), patch.object(runner, 'git', fake_git):
                runner.publish(batch, True)
            self.assertFalse(any(c[0] in ('add','commit','push') for c in calls))
            self.assertFalse((batch / 'publication.json').exists())

    def test_existing_destination_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); batch = root / 'batch'; batch.mkdir()
            (batch / 'publication-payload').mkdir()
            runner.write(batch / 'request.json', dict(seed=999))
            runner.write(batch / 'payload-hashes.json', {})
            (root / 'work/decklist-source/rakdos/forge-tests/reports/seed999').mkdir(parents=True)
            def fake_git(repo, *args):
                return 'main' if args[0] == 'branch' else 'https://github.com/cwppt/MTG-deck-lists.git' if args[0] == 'remote' else ''
            with patch.object(runner, 'ROOT', root), patch.object(runner, 'final_gate'), patch.object(runner, 'git', fake_git):
                with self.assertRaisesRegex(RuntimeError, 'refusing overwrite'): runner.publish(batch, True)

    def test_worker_lock_prevents_duplicate(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(runner, 'STORE', Path(temp)):
            with runner.WorkerLock():
                with self.assertRaisesRegex(RuntimeError, 'Another batch'): runner.WorkerLock().__enter__()

    def test_resume_preserves_valid_and_interrupted_results(self):
        with tempfile.TemporaryDirectory() as temp:
            batch = Path(temp); output = batch / 'R2'; output.mkdir()
            original = output / 'seed-100'; original.mkdir(); valid_fixture(original)
            attempts = runner.classify(original, 100, 0)
            attempts[0].update(attemptIndex=0, rawDirectory=str(original))
            runner.cohort.write_state(output, attempts, 2, 5,
                [dict(runSeed=100, requestedGames=1, attemptedGames=1, processExitCode=0, rawDirectory=str(original))])
            interrupted = output / 'seed-101'; interrupted.mkdir()
            (interrupted / 'commander-seed101.log').write_text('Simulation Attempt: gameIndex=0 gameId=1 runSeed=101\n')
            launched = []
            class FakeProcess:
                pid = 123; returncode = 0
                def __init__(self, command, **kwargs):
                    seed = int(command[command.index('-s')+1]); launched.append(seed)
                    valid_fixture(Path(command[command.index('--metrics-csv')+1]).parent, seed)
                def poll(self): return 0
            request = dict(seed=100, target=2, maxAttempts=5, java='java', jar='jar', timeoutSeconds=180)
            with patch.object(runner, 'verify'), patch.object(runner, 'progress'), patch.object(runner.subprocess, 'Popen', FakeProcess):
                runner.run_variant(batch, request, 'R2', 'Rivaz-R2.dck')
            saved = runner.cohort.load_jsonl(output / 'attempts.jsonl')
            self.assertEqual(launched, [102])
            self.assertEqual([a['simulationStatus'] for a in saved], ['VALID','INTERRUPTED','VALID'])
            self.assertEqual((original / 'commander-seed100.log').read_text().count('Game Result:'), 1)

    def test_empty_launches_obey_attempt_cap(self):
        with tempfile.TemporaryDirectory() as temp:
            batch = Path(temp); launched = []
            class FakeProcess:
                pid = 123; returncode = 1
                def __init__(self, command, **kwargs): launched.append(int(command[command.index('-s')+1]))
                def poll(self): return 1
            request = dict(seed=100, target=1, maxAttempts=2, java='java', jar='jar', timeoutSeconds=180)
            with patch.object(runner, 'verify'), patch.object(runner, 'progress'), patch.object(runner.subprocess, 'Popen', FakeProcess):
                with self.assertRaisesRegex(RuntimeError, 'maximum attempts'): runner.run_variant(batch, request, 'R2', 'Rivaz-R2.dck')
            self.assertEqual(launched, [100,101])
            self.assertEqual(runner.read(batch / 'R2/cohort-summary.json')['attemptedGames'], 2)

    def test_resume_reconciles_ledger_written_before_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            batch = Path(temp); output = batch / 'R2'; output.mkdir()
            original = output / 'seed-100'; original.mkdir(); valid_fixture(original)
            attempts = runner.classify(original, 100, 0)
            attempts[0].update(attemptIndex=0, rawDirectory=str(original))
            runner.cohort.write_state(output, attempts, 1, 3, [])
            with patch.object(runner, 'verify'), patch.object(runner, 'progress'), patch.object(runner.subprocess, 'Popen') as launch:
                runner.run_variant(batch, dict(seed=100, target=1, maxAttempts=3), 'R2', 'Rivaz-R2.dck')
            launch.assert_not_called()
            self.assertEqual(len(runner.cohort.load_jsonl(output / 'attempts.jsonl')), 1)
            self.assertEqual(len(runner.read(output / 'cohort-summary.json')['batches']), 1)

    def test_push_failure_retries_saved_commit_without_rerunning(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); batch = root / 'batch'; batch.mkdir()
            payload = batch / 'publication-payload'; payload.mkdir(); (payload / 'report.json').write_text('{}')
            runner.write(batch / 'request.json', dict(seed=999))
            runner.write(batch / 'payload-hashes.json', runner.inventory(payload))
            calls = []; fail_push = [True]
            def fake_git(repo, *args):
                calls.append(args)
                if args[0] == 'branch': return 'main'
                if args[0] == 'remote': return 'https://github.com/cwppt/MTG-deck-lists.git'
                if args[0] == 'rev-parse': return 'test-commit'
                if args[0] == 'diff' and '--cached' in args: return 'rakdos/forge-tests/reports/seed999/report.json'
                if args[0] == 'push' and fail_push[0]: raise RuntimeError('network unavailable')
                return ''
            with patch.object(runner, 'ROOT', root), patch.object(runner, 'final_gate'), patch.object(runner, 'progress'), patch.object(runner, 'git', fake_git):
                with self.assertRaisesRegex(RuntimeError, 'network unavailable'): runner.publish(batch)
                self.assertEqual(runner.read(batch / 'publication.json')['commit'], 'test-commit')
                fail_push[0] = False; runner.publish(batch)
            self.assertEqual(sum(c[0] == 'commit' for c in calls), 1)
            self.assertEqual(sum(c[0] == 'push' for c in calls), 2)
            self.assertEqual(runner.read(batch / 'publication.json')['state'], 'PUBLISHED')

    def test_completed_unledgered_game_recovered_once_without_fake_exit(self):
        with tempfile.TemporaryDirectory() as temp:
            batch=Path(temp); output=batch/'R2'; output.mkdir(); directory=output/'seed-100'; directory.mkdir()
            valid_fixture(directory)
            with (directory/'commander-seed100.log').open('a') as stream:
                stream.write('gameResults attempted=1 valid=1 failed=0 draws=0\n')
            request=dict(seed=100,target=2,maxAttempts=5)
            with patch.object(runner,'independent_validation',return_value={'validation':'PASS','runs':[]}):
                runner.reconcile_variant(batch,request,'R2')
                runner.reconcile_variant(batch,request,'R2')
            saved=runner.cohort.load_jsonl(output/'attempts.jsonl')
            self.assertEqual(len(saved),1); self.assertTrue(saved[0]['valid'])
            self.assertIsNone(saved[0]['processExitCode']); self.assertTrue(saved[0]['workerInterrupted'])
            self.assertFalse((directory/'exit.json').exists())

    def test_completed_previously_interrupted_game_promoted_without_duplicate(self):
        with tempfile.TemporaryDirectory() as temp:
            batch=Path(temp); output=batch/'R2'; output.mkdir(); directory=output/'seed-100'; directory.mkdir()
            valid_fixture(directory)
            with (directory/'commander-seed100.log').open('a') as stream:
                stream.write('gameResults attempted=1 valid=1 failed=0 draws=0\n')
            original=runner.classify(directory,100,-1)[0]
            original.update(attemptIndex=0,rawDirectory=str(directory),simulationStatus='INTERRUPTED')
            runner.cohort.write_state(output,[original],2,5,[dict(runSeed=100,attemptedGames=1,processExitCode=-1)])
            with patch.object(runner,'independent_validation',return_value={'validation':'PASS','runs':[]}):
                runner.reconcile_variant(batch,dict(seed=100,target=2,maxAttempts=5),'R2')
            saved=runner.cohort.load_jsonl(output/'attempts.jsonl')
            self.assertEqual(len(saved),1); self.assertTrue(saved[0]['valid'])
            audit=runner.cohort.load_jsonl(batch/'reconciliation.jsonl')
            self.assertEqual(audit[0]['original']['simulationStatus'],'INTERRUPTED')

    def test_duplicate_identity_blocks_recovery(self):
        with tempfile.TemporaryDirectory() as temp:
            batch=Path(temp); output=batch/'R2'; output.mkdir()
            p=output/'attempts.jsonl'; item=dict(runSeed=100,gameIndex=0)
            p.write_text(json.dumps(item)+'\n'+json.dumps(item)+'\n')
            with self.assertRaisesRegex(RuntimeError,'Duplicate seed/game'):
                runner.reconcile_variant(batch,dict(seed=100,target=2,maxAttempts=5),'R2')

    def test_nonzero_engine_exit_is_not_salvaged(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp); valid_fixture(directory)
            with self.assertRaisesRegex(RuntimeError,'not a clean valid'):
                runner.completed_attempt(directory,100,7)

    def test_unknown_exit_requires_terminal_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp); valid_fixture(directory)
            with self.assertRaisesRegex(RuntimeError,'Missing clean terminal'):
                runner.completed_attempt(directory,100,None)

    def test_live_child_blocks_recovery_before_ledger_mutation(self):
        with tempfile.TemporaryDirectory() as temp:
            batch=Path(temp); output=batch/'R2'; output.mkdir(); directory=output/'seed-100'; directory.mkdir()
            runner.write(directory/'process.json',{'pid':123})
            runner.write(directory/'command.json',['java.exe','-s','100'])
            current=dict(pid=123,name='java.exe',executable='C:/Java/java.exe',commandLine='java.exe -s 100',creationUtc='2026-10-13T00:00:00+00:00')
            with patch.object(runner,'process_snapshot',return_value={123:current}):
                with self.assertRaisesRegex(RuntimeError,'Orphan process'):
                    runner.reconcile_variant(batch,dict(seed=100,target=2,maxAttempts=5),'R2')
            self.assertFalse((output/'attempts.jsonl').exists())

    def test_reviewed_runner_revision_keeps_original_request_and_input_checks(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); store=root/'store'; batch=store/'seed100'; batch.mkdir(parents=True)
            old=root/'original.py'; old.write_text('old source')
            live=root/'CommanderBatch.py'; live.write_text('new source')
            jar=root/'jar'; jar.write_text('original jar')
            request=dict(seed=100,assets=[dict(path=str(live),sha256=runner.sha(old)),dict(path=str(jar),sha256=runner.sha(jar))],ai=dict(externalAIEnabled=False))
            (batch/'runner-revisions.jsonl').write_text(json.dumps(dict(previousSha256=runner.sha(old),previousSource=str(old),sha256=runner.sha(live)))+'\n')
            with patch.object(runner,'HERE',root),patch.object(runner,'STORE',store):
                runner.verify(request)
                jar.write_text('changed jar')
                with self.assertRaisesRegex(RuntimeError,'Input changed'): runner.verify(request)
            self.assertEqual(request['assets'][0]['sha256'],runner.sha(old))


if __name__ == '__main__': unittest.main()
