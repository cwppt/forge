import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import sys

spec=importlib.util.spec_from_file_location('cohort',Path(__file__).with_name('Run-ValidCommanderCohort.py'))
cohort=importlib.util.module_from_spec(spec); spec.loader.exec_module(cohort)


def fixture(directory, seed, count, fail_first=False):
    games=[]; logs=[]; console=[]
    for index in range(count):
        failure=dict(category='java.lang.NumberFormatException',message='ChosenPlayerUID',
                     card='Guardian Archon',deckIdentifier='Breena',playerSeat=3,stage='ability-evaluation')
        failed=fail_first and index==0
        game=dict(gameId=index+1,gameIndex=index,runSeed=seed,durationMs=10,finalTurnCount=1,
                  winner='Lathliss',draw=False,timedOut=False,simulationError=None,
                  valid=not failed,engineFailures=[failure]*13 if failed else [],
                  candidateExposure=[dict(playerSeat=3,deckIdentifier='Breena',card='Guardian Archon')])
        games.append(game)
        for event,(kind,message) in enumerate([('TURN','Turn 1'),('GAME_OUTCOME','Lathliss has won because all opponents have lost'),('MATCH_RESULTS','Lathliss: 1')]):
            logs.append(dict(gameId=index+1,gameIndex=index,runSeed=seed,eventIndex=event,type=kind,message=message))
        console.append(f'Simulation Attempt: gameIndex={index} gameId={index+1} runSeed={seed}')
        if failed:
            console += ['java.util.concurrent.ExecutionException: java.lang.NumberFormatException: ChosenPlayerUID\n\tat worker\nCaused by: java.lang.NumberFormatException: ChosenPlayerUID\n\tat parser']*13
        console.append(f'Game Result: Game {index+1} ended in 10 ms. Lathliss has won!')
    prefix=directory/f'commander-seed{seed}'
    for suffix,rows in [('games',games),('gamelog',logs),('decisions',[])]:
        Path(str(prefix)+f'.{suffix}.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in rows))
    Path(str(prefix)+'.metrics.csv').write_text('decisionId,gameId,gameIndex,runSeed,decisionType\n')
    Path(str(prefix)+'.log').write_text('\n'.join(console)+'\n')


class ValidCohortTests(unittest.TestCase):
    def test_excluded_seeds_never_launch_or_consume_attempts(self):
        with tempfile.TemporaryDirectory() as temp:
            output=Path(temp);config=output/'config.json'
            config.write_text(json.dumps(dict(TimeoutSeconds=1,ExcludedSeeds=[100,102])))
            called=[]
            class FakeProcess:
                def __init__(self,command,**kwargs):
                    seed=int(command[command.index('-Seed')+1]);called.append(seed)
                    fixture(Path(command[command.index('-Directory')+1]),seed,1)
                def wait(self,timeout=None):return 0
            argv=['runner','--config',str(config),'--output',str(output),'--target','3','--max-attempts','3','--batch-size','1','--seed-start','100']
            with patch.object(sys,'argv',argv),patch.object(cohort.subprocess,'Popen',FakeProcess):
                self.assertEqual(cohort.main(),0)
            summary=json.loads((output/'cohort-summary.json').read_text())
            self.assertEqual(called,[101,103,104]);self.assertEqual(summary['attemptedGames'],3)
            self.assertEqual(summary['failedGames'],0);self.assertEqual(summary['skippedSeedCount'],2)
            self.assertEqual([s['runSeed'] for s in summary['skippedSeeds']],[100,102])
            self.assertFalse((output/'seed-100').exists())

    def test_resume_retains_skip_evidence(self):
        with tempfile.TemporaryDirectory() as temp:
            output=Path(temp);config=output/'config.json';config.write_text(json.dumps(dict(TimeoutSeconds=1,ExcludedSeeds=[100])))
            cohort.write_state(output,[],1,1,[],[dict(runSeed=100,status='SKIPPED',attemptedGames=0,reason='known blocker')])
            called=[]
            class FakeProcess:
                def __init__(self,command,**kwargs):
                    seed=int(command[command.index('-Seed')+1]);called.append(seed)
                    fixture(Path(command[command.index('-Directory')+1]),seed,1)
                def wait(self,timeout=None):return 0
            argv=['runner','--resume','--config',str(config),'--output',str(output),'--target','1','--max-attempts','1','--batch-size','1','--seed-start','100']
            with patch.object(sys,'argv',argv),patch.object(cohort.subprocess,'Popen',FakeProcess):self.assertEqual(cohort.main(),0)
            self.assertEqual(called,[101]);self.assertEqual(json.loads((output/'cohort-summary.json').read_text())['skippedSeedCount'],1)

    def test_required_role_export_cannot_silently_disappear_or_truncate(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);fixture(directory,100,1)
            self.assertTrue(cohort.classify_batch(directory,100,1,False)[0]['valid'])
            self.assertFalse(cohort.classify_batch(directory,100,1,False,require_roles=True)[0]['valid'])
            path=directory/'commander-seed100.roles.jsonl'
            rows=[dict(gameId=1,gameIndex=0,runSeed=100,eventIndex=i,type=kind,rawTurn=1)
                  for i,kind in enumerate(['START','TURN','SNAPSHOT','END'])]
            path.write_text(''.join(json.dumps(e)+'\n' for e in rows))
            self.assertTrue(cohort.classify_batch(directory,100,1,False,require_roles=True)[0]['valid'])
            rows[-1]['eventIndex']=4;path.write_text(''.join(json.dumps(e)+'\n' for e in rows))
            self.assertFalse(cohort.classify_batch(directory,100,1,False,require_roles=True)[0]['valid'])
    def test_mapping_roots_ignore_ids_and_do_not_blame_evaluated_card(self):
        first=dict(category='java.lang.RuntimeException',message="Couldn't map Breena, the Demagogue (403)/232265401",
                   card='Comet Storm',stage='candidate-copy',stackTrace='at Player.copyCommandersToSnapshot(Player.java:1)')
        second=dict(first,message="Couldn't map Breena, the Demagogue (55)/999")
        self.assertEqual(cohort.root_signature(first),cohort.root_signature(second))
        self.assertEqual(cohort.implicated_card(first),'Breena, the Demagogue')
        self.assertNotEqual(cohort.implicated_card(first),first['card'])

    def test_candidate_display_type_is_not_part_of_implicated_card_name(self):
        failure=dict(category='java.lang.RuntimeException',message="Couldn't map Pilgrim's Eye - Creature 1 / 1/1227878063",
                     card='Soul Snare',stage='candidate-copy')
        self.assertEqual(cohort.implicated_card(failure),"Pilgrim's Eye")

    def test_copy_failure_context_counts_exposure_without_causal_attribution(self):
        attempt=dict(valid=False,simulationStatus='ENGINE_FAILED',runSeed=100,winner=None,draw=None,
            recordedOutcome={},candidateExposure=[],failures=[dict(category='java.lang.RuntimeException',
                message="Couldn't map Breena, the Demagogue (5)/999",stage='candidate-copy',
                card='Comet Storm',playerSeat=0,deckIdentifier='Lathliss')])
        summary=cohort.summarize([attempt],1,3,[])
        association=summary['cardExposureAssociations'][0]
        self.assertEqual(association['exposedGames'],1)
        self.assertEqual(association['failedEvaluationsAtCard'],1)
        self.assertEqual(association['attributableFailedGames'],0)

    def test_failure_interval_and_exact_association_are_bounded(self):
        self.assertEqual(cohort.fisher_two_sided(1,0,0,1),1.)
        low,high=cohort.wilson(0,25)
        self.assertGreaterEqual(low,0); self.assertLess(high,.2)

    def test_thirteen_exceptions_fail_one_game_not_thirteen(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp); fixture(directory,100,4,True)
            attempts=cohort.classify_batch(directory,100,4,False)
            self.assertEqual([a['valid'] for a in attempts],[False,True,True,True])
            self.assertEqual(len(attempts[0]['failures']),13)
            self.assertIsNone(attempts[0]['winner'])
            self.assertEqual(attempts[0]['recordedOutcome']['winner'],'Lathliss')
            summary=cohort.summarize(attempts,3,10,[])
            self.assertEqual(summary['attemptedGames'],4)
            self.assertEqual(summary['validGames'],3)
            self.assertEqual(summary['engineFailures'],1)
            self.assertEqual(summary['wins'],{'Lathliss':3})
            self.assertEqual(summary['failureRate'],.25)
            self.assertEqual(summary['failuresByCard']['Guardian Archon']['failedGames'],1)

    def test_timeout_is_failed_not_a_valid_draw(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp); fixture(directory,100,1)
            path=directory/'commander-seed100.games.jsonl'; game=json.loads(path.read_text())
            game['timedOut']=True; game['valid']=False; path.write_text(json.dumps(game)+'\n')
            attempt=cohort.classify_batch(directory,100,1,False)[0]
            self.assertFalse(attempt['valid']); self.assertEqual(attempt['simulationStatus'],'TIMEOUT')

    def test_csv_corruption_and_orphan_log_are_not_valid(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp); fixture(directory,100,1)
            with (directory/'commander-seed100.metrics.csv').open('a') as stream: stream.write('too,few\n')
            self.assertFalse(cohort.classify_batch(directory,100,1,False)[0]['valid'])
            fixture(directory,100,1)
            with (directory/'commander-seed100.gamelog.jsonl').open('a') as stream:
                stream.write(json.dumps(dict(gameId=99,gameIndex=99))+'\n')
            self.assertIn('orphan-log',cohort.classify_batch(directory,100,1,False)[0]['telemetryErrors'])

    def test_console_only_failure_is_preserved_without_guessing_card(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp); fixture(directory,100,1)
            path=directory/'commander-seed100.log'
            path.write_text('java.lang.IllegalStateException: unknown defect\n\tat engine\n'+path.read_text())
            attempt=cohort.classify_batch(directory,100,1,False)[0]
            self.assertFalse(attempt['valid']); self.assertIsNone(attempt['failures'][0]['card'])

    def test_crashed_started_game_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp)
            (directory/'commander-seed100.log').write_text('Simulation Attempt: gameIndex=0 gameId=1 runSeed=100\njava.lang.StackOverflowError\n\tat engine\n')
            attempts=cohort.classify_batch(directory,100,4,False,1)
            self.assertEqual(len(attempts),1); self.assertFalse(attempts[0]['valid'])

    def test_replacement_loop_reaches_exact_target_with_fresh_seed(self):
        self.run_mocked_cohort(target=4,maximum=8,always_fail=False,expected_valid=4,expected_attempts=5)

    def test_all_failed_stops_at_attempt_cap(self):
        self.run_mocked_cohort(target=1,maximum=3,always_fail=True,expected_valid=0,expected_attempts=3)

    def test_resume_preserves_interrupted_attempt_and_uses_fresh_seed(self):
        with tempfile.TemporaryDirectory() as temp:
            output=Path(temp); config=output/'config.json'; config.write_text(json.dumps(dict(TimeoutSeconds=1)))
            cohort.write_state(output,[],1,3,[])
            interrupted=output/'seed-100'; interrupted.mkdir()
            (interrupted/'commander-seed100.log').write_text('Simulation Attempt: gameIndex=0 gameId=1 runSeed=100\n')
            called=[]
            class FakeProcess:
                def __init__(self,command,**kwargs):
                    seed=int(command[command.index('-Seed')+1]); called.append(seed)
                    fixture(Path(command[command.index('-Directory')+1]),seed,1)
                def wait(self,timeout=None): return 0
            argv=['runner','--resume','--config',str(config),'--output',str(output),'--target','1',
                  '--max-attempts','3','--batch-size','1','--seed-start','100']
            with patch.object(sys,'argv',argv),patch.object(cohort.subprocess,'Popen',FakeProcess):
                self.assertEqual(cohort.main(),0)
            attempts=cohort.load_jsonl(output/'attempts.jsonl')
            self.assertEqual(called,[101]); self.assertEqual(len(attempts),2)
            self.assertEqual(attempts[0]['simulationStatus'],'INTERRUPTED')
            self.assertFalse(attempts[0]['valid']); self.assertTrue(attempts[1]['valid'])
            self.assertIsNone(attempts[0]['winner'])

    def run_mocked_cohort(self,target,maximum,always_fail,expected_valid,expected_attempts):
        with tempfile.TemporaryDirectory() as temp:
            output=Path(temp); config=output/'config.json'; config.write_text(json.dumps(dict(TimeoutSeconds=1)))
            called=[]
            class FakeProcess:
                def __init__(self,command,**kwargs):
                    seed=int(command[command.index('-Seed')+1]); count=int(command[command.index('-Games')+1])
                    directory=Path(command[command.index('-Directory')+1]); called.append(seed)
                    fixture(directory,seed,count,always_fail or len(called)==1)
                def wait(self,timeout=None): return 0
            argv=['runner','--config',str(config),'--output',str(output),'--target',str(target),
                  '--max-attempts',str(maximum),'--batch-size','4','--seed-start','100']
            with patch.object(sys,'argv',argv),patch.object(cohort.subprocess,'Popen',FakeProcess):
                result=cohort.main()
            summary=json.loads((output/'cohort-summary.json').read_text())
            self.assertEqual(summary['validGames'],expected_valid)
            self.assertEqual(summary['attemptedGames'],expected_attempts)
            self.assertEqual(len(called),len(set(called)))
            self.assertEqual(result,0 if expected_valid==target else 2)


if __name__=='__main__': unittest.main()
