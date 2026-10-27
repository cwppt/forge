import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch
import CommanderBatch as api
import ParallelCommanderPool as pool

class IdentityTests(unittest.TestCase):
    def fixture(self, directory, modern=False):
        live=dict(pid=20704,name='java.exe',executable='C:/Java/java.exe',commandLine='"C:/Java/java.exe" -jar forge.jar sim -s 2502 --game-results-jsonl "C:/batch/R2/seed-2502/games.jsonl"',creationUtc='2026-10-13T00:20:00+00:00')
        record=dict(pid=20704,startedUtc='2026-10-13T00:20:01+00:00')
        if modern: record['processIdentity']=live.copy()
        api.write(directory/'process.json',record)
        api.write(directory/'command.json',api.command_arguments(live['commandLine']))
        return live
    def test_reused_pid_windows_service_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp); self.fixture(directory)
            live=dict(pid=20704,name='svchost.exe',executable='C:/Windows/system32/svchost.exe',commandLine='svchost.exe -s DevicesFlowUserSvc',creationUtc='2026-10-13T09:10:57+00:00')
            with patch.object(api,'kill_tree') as kill:
                api.assert_child_stopped(directory,{20704:live}); kill.assert_not_called()
    def test_genuine_orphan_legacy_and_modern(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)
            for modern in (False,True):
                live=self.fixture(directory,modern)
                with self.assertRaisesRegex(RuntimeError,'Orphan process'): api.assert_child_stopped(directory,{20704:live})
    def test_missing_process_safe(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp); self.fixture(directory); api.assert_child_stopped(directory,{})
    def test_reused_java_pid_creation_and_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)
            for modern in (False,True):
                live=self.fixture(directory,modern); live['creationUtc']='2026-10-13T09:10:57+00:00'
                api.assert_child_stopped(directory,{20704:live})
                live=self.fixture(directory,modern); live['commandLine']='java.exe -jar another.jar'
                api.assert_child_stopped(directory,{20704:live})
    def test_unreadable_java_identity_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp); live=self.fixture(directory); live['commandLine']=None
            with self.assertRaisesRegex(RuntimeError,'Cannot establish'): api.assert_child_stopped(directory,{20704:live})
    def test_batch_scan_detects_jvm_descendant_before_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            batch=Path(tmp); output=batch/'R2'; output.mkdir()
            live=dict(pid=999,name='java.exe',commandLine='java.exe -Duser.home='+str(batch/'R2/seed-2502/runtime/home'))
            with patch.object(api,'process_snapshot',return_value={999:live}),self.assertRaisesRegex(RuntimeError,'belongs to this batch'):
                pool.preflight(batch,dict(seed=2500,maxAttempts=300),api)
            self.assertFalse((output/'attempts.jsonl').exists())
    def test_capture_checks_reserved_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp); live=self.fixture(directory)
            command=api.read(directory/'command.json')
            with patch.object(api,'process_snapshot',return_value={20704:live}):
                self.assertEqual(api.capture_process_identity(20704,command),live)
                with self.assertRaisesRegex(RuntimeError,'disagrees'):
                    api.capture_process_identity(20704,['java.exe','-s','9999'])
            with patch.object(api,'process_snapshot',return_value={}): self.assertIsNone(api.capture_process_identity(20704,command))

    def test_reviewed_revisions_are_scoped_to_each_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); store=root/'store'; batch=store/'seed100'; batch.mkdir(parents=True)
            assets=[]; revisions=[]
            for name in ('CommanderBatch.py','ParallelCommanderPool.py'):
                old=root/(name+'.old'); old.write_text('original '+name)
                live=root/name; live.write_text('reviewed '+name)
                assets.append(dict(path=str(live),sha256=api.sha(old)))
                revisions.append(dict(path=str(live),previousSha256=api.sha(old),previousSource=str(old),sha256=api.sha(live)))
            (batch/'runner-revisions.jsonl').write_text(''.join(__import__('json').dumps(r)+'\n' for r in revisions))
            request=dict(seed=100,assets=assets,ai=dict(externalAIEnabled=False))
            with patch.object(api,'HERE',root),patch.object(api,'STORE',store):
                api.verify(request)
                (root/'ParallelCommanderPool.py').write_text('unreviewed modification')
                with self.assertRaisesRegex(RuntimeError,'Input changed'): api.verify(request)

    def test_inspection_failure_blocks_recovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            with patch.object(api,'process_snapshot',side_effect=RuntimeError('Cannot inspect')),self.assertRaisesRegex(RuntimeError,'Cannot inspect'):
                pool.preflight(Path(tmp),{},api)

class FinalizeOnlyTests(unittest.TestCase):
    def test_finalize_never_starts_simulations(self):
        import contextlib
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmp:
            store=Path(tmp);batch=store/'seed3500';batch.mkdir()
            api.write(batch/'request.json',dict(seed=3500,target=100,publishOnCompletion=True))
            with patch.object(api,'STORE',store),patch.object(api,'WorkerLock',contextlib.nullcontext),patch.object(api,'verify'),patch.object(api,'complete') as complete,patch.object(api,'publish') as publish,patch.object(pool,'run_batch',side_effect=AssertionError('must not rerun')):
                api.work(SimpleNamespace(mode='finalize',batch=str(batch)))
                complete.assert_called_once();publish.assert_called_once()

class InaccessibleIdentityTests(unittest.TestCase):
    def test_old_filetime_reuse_bypasses_unreadable_executable(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)
            original=api.datetime.fromisoformat('2026-10-13T00:20:00+00:00').timestamp()
            api.write(directory/'process.json',dict(pid=99,processIdentity=dict(creationFileTime=int(original*10000000)+116444736000000000,imagePath='java.exe')))
            current=dict(pid=99,name='java.exe',creationUtc='2026-10-13T14:20:00Z',executable=None,commandLine=None)
            api.assert_child_stopped(directory,{99:current})
    def test_old_filetime_live_unreadable_identity_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp);created=api.datetime.fromisoformat('2026-10-13T00:20:00+00:00').timestamp()
            api.write(directory/'process.json',dict(pid=99,processIdentity=dict(creationFileTime=int(created*10000000)+116444736000000000,imagePath='java.exe')))
            current=dict(pid=99,name='java.exe',creationUtc='2026-10-13T00:20:00Z',executable=None,commandLine=None)
            with self.assertRaisesRegex(RuntimeError,'Cannot establish'):api.assert_child_stopped(directory,{99:current})
    def test_unrelated_protected_process_does_not_need_executable(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp);api.write(directory/'process.json',dict(pid=99,processIdentity=dict(creationFileTime=1,imagePath='java.exe')))
            api.assert_child_stopped(directory,{99:dict(pid=99,name='svchost.exe',executable=None,commandLine=None)})

if __name__=='__main__': unittest.main()
