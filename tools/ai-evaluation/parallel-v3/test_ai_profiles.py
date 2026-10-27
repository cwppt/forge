from pathlib import Path
from types import SimpleNamespace
import tempfile
import threading
import unittest
from unittest.mock import patch
import CommanderBatch as api
import ParallelCommanderPool as pool


class AiProfileTests(unittest.TestCase):
    def test_profile_is_frozen_and_must_be_preserved_on_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);store=root/'work/commander-batches';decks=root/'work/commander-smoke-decks';decks.mkdir(parents=True)
            jar=root/'forge-gui-desktop/target/forge-gui-desktop-2.0.16-SNAPSHOT-jar-with-dependencies.jar';jar.parent.mkdir(parents=True);jar.write_text('fixture')
            for filename in [v[1] for v in api.VARIANTS]+api.OPPONENTS:(decks/filename).write_text('fixture')
            lands={f:n for _,f,n in api.VARIANTS}
            args=SimpleNamespace(seed=1000,games=1,max_attempts=3,max_parallel=5,publish=False,resume=False,timeout=300,ai_profile='bounded-advisory')
            with patch.object(api,'ROOT',root),patch.object(api,'STORE',store),patch.object(api,'git',return_value='fixture'),patch.object(api,'land_count',side_effect=lambda p:lands[p.name]),patch.dict(api.os.environ,APPDATA=str(root/'appdata')):
                api.prepare(args)
                request=api.read(store/'seed1000/request.json')
                self.assertEqual(request['aiProfile'],'bounded-advisory')
                self.assertEqual(request['javaOptions'],api.AI_PROFILES['bounded-advisory'])
                self.assertFalse(request['ai']['externalAIEnabled'])
                args.resume=True;args.ai_profile='standard'
                with self.assertRaisesRegex(RuntimeError,'saved AI profile'):api.prepare(args)
                args.ai_profile='bounded-advisory';api.prepare(args)
                request['javaOptions'].append('-Xmx100g')
                with self.assertRaisesRegex(RuntimeError,'AI profile options'):api.verify(request)

    def test_standard_and_bounded_commands_keep_resource_and_validation_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            for profile in api.AI_PROFILES:
                directory=Path(tmp)/profile;directory.mkdir()
                job=dict(variant='R2',filename='Rivaz-R2.dck',runSeed=2502,attemptIndex=0,rawDirectory=str(directory))
                request=dict(java='java.exe',jar='forge.jar',heapGiB=4,activeProcessorCount=2,timeoutSeconds=300,aiProfile=profile,javaOptions=api.AI_PROFILES[profile])
                class Process:
                    pid=123;returncode=0
                    def poll(self):return 0
                item=pool.failure(job,0,'TIMEOUT','fixture')
                with patch.object(pool,'isolated_runtime',return_value=(directory,{},directory,directory)),patch.object(pool.subprocess,'Popen',return_value=Process()) as launch,patch.object(api,'capture_process_identity',return_value=None),patch.object(api,'classify',return_value=[item]):
                    pool.execute(job,directory,request,pool.ActiveGames(),threading.Event(),api)
                command=launch.call_args.args[0]
                options=[arg for arg in command if arg.startswith('-Dforge.ai.')]
                self.assertEqual(options,api.AI_PROFILES[profile])
                self.assertIn('-Xmx4g',command);self.assertIn('-XX:ActiveProcessorCount=2',command)
                self.assertIn('--capture-simulation-failures',command);self.assertIn('--game-results-jsonl',command)
                for option in options:self.assertLess(command.index(option),command.index('-jar'))


if __name__=='__main__':unittest.main()
