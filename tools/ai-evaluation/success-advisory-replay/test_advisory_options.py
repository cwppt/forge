import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch
import CommanderBatch as api
import ParallelCommanderPool as pool


class AdvisoryOptionsTests(unittest.TestCase):
    def test_only_explicit_budget_profile_is_passed_before_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory=Path(tmp)
            job=dict(variant='R2',filename='Rivaz-R2.dck',runSeed=2502,attemptIndex=0,rawDirectory=str(directory))
            options=['-Dforge.ai.evalDiagnostics=true','-Dforge.ai.evalBudgetMs=500','-Dforge.ai.evalWorkLimit=2000']
            request=dict(java='java.exe',jar='forge.jar',heapGiB=4,activeProcessorCount=2,timeoutSeconds=300,javaOptions=options)
            class Process:
                pid=123;returncode=0
                def poll(self):return 0
            result=pool.failure(job,0,'TIMEOUT','fixture')
            with patch.object(pool,'isolated_runtime',return_value=(directory,{},directory,directory)),patch.object(pool.subprocess,'Popen',return_value=Process()) as launch,patch.object(api,'capture_process_identity',return_value=None),patch.object(api,'classify',return_value=[result]):
                pool.execute(job,directory,request,pool.ActiveGames(),threading.Event(),api)
            command=launch.call_args.args[0]
            self.assertEqual(command[1:4],options)
            self.assertIn('-Xmx4g',command);self.assertIn('-XX:ActiveProcessorCount=2',command)
            self.assertEqual(command[command.index('-c')+1],'300')
            self.assertLess(command.index(options[0]),command.index('-jar'))
            request['javaOptions']=['-Dunexpected=true']
            item=pool.execute(job,directory,request,pool.ActiveGames(),threading.Event(),api)
            self.assertFalse(item['valid']);self.assertEqual(item['simulationStatus'],'LAUNCH_FAILED')


if __name__=='__main__':unittest.main()
