"""Separate run identity; reuse the tested finite supervisor without enabling combat v1."""
import sys
sys.dont_write_bytecode=True
import cost_performance as api
sys.modules['combat_performance']=api
import combat_run as runner

if __name__=='__main__':
    try:
        if '--freeze' in sys.argv:runner.freeze()
        else:runner.run()
    except BaseException:
        runner.write(runner.R/'bootstrap-or-run-error.json',dict(error=runner.traceback.format_exc(),utc=runner.now()))
        raise
