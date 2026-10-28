"""Read CPU/kernel peak counters for this supervisor's own child process only."""
import ctypes,json,threading,time
from ctypes import wintypes

def launch_with_counters(original,directory,command,cwd,env,limit=330):
    kernel=ctypes.WinDLL('kernel32',use_last_error=True);psapi=ctypes.WinDLL('psapi',use_last_error=True)
    kernel.OpenProcess.argtypes=[wintypes.DWORD,wintypes.BOOL,wintypes.DWORD];kernel.OpenProcess.restype=wintypes.HANDLE
    kernel.CloseHandle.argtypes=[wintypes.HANDLE]
    kernel.GetProcessTimes.argtypes=[wintypes.HANDLE,ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME),ctypes.POINTER(wintypes.FILETIME)]
    class Memory(ctypes.Structure):
        _fields_=[('cb',wintypes.DWORD),('PageFaultCount',wintypes.DWORD)]+[(x,ctypes.c_size_t) for x in ['PeakWorkingSetSize','WorkingSetSize','QuotaPeakPagedPoolUsage','QuotaPagedPoolUsage','QuotaPeakNonPagedPoolUsage','QuotaNonPagedPoolUsage','PagefileUsage','PeakPagefileUsage']]
    psapi.GetProcessMemoryInfo.argtypes=[wintypes.HANDLE,ctypes.POINTER(Memory),wintypes.DWORD]
    done=threading.Event();data=dict(cpuSeconds=None,peakWorkingSetBytes=None,finalMemoryQuerySucceeded=False);handle=[None]
    def sample(final=False):
        memory=Memory();memory.cb=ctypes.sizeof(memory)
        if psapi.GetProcessMemoryInfo(handle[0],ctypes.byref(memory),memory.cb):
            data['peakWorkingSetBytes']=max(data['peakWorkingSetBytes'] or 0,memory.PeakWorkingSetSize)
            if final:data['finalMemoryQuerySucceeded']=True
    def monitor():
        try:
            while not done.is_set():
                try:pid=json.loads((directory/'process.json').read_text())['pid'];handle[0]=kernel.OpenProcess(0x410,False,pid)
                except (OSError,ValueError):pass
                if handle[0]:break
                done.wait(.01)
            if not handle[0]:data['unavailableReason']='Child process handle unavailable';return
            while not done.wait(.5):sample()
            sample(True)
            values=[wintypes.FILETIME() for _ in range(4)]
            if kernel.GetProcessTimes(handle[0],*[ctypes.byref(x) for x in values]):
                data['cpuSeconds']=sum((x.dwHighDateTime<<32)|x.dwLowDateTime for x in values[2:])/10_000_000
        except Exception as ex:data['unavailableReason']=str(ex)
        finally:
            if handle[0]:kernel.CloseHandle(handle[0])
    worker=threading.Thread(target=monitor,daemon=True);worker.start()
    try:return original(directory,command,cwd,env,limit)
    finally:
        done.set();worker.join(5);data['memoryScope']='Kernel lifetime peak if final query succeeded; otherwise last sampled kernel peak. CPU includes this child only, all its threads.'
        (directory/'process-counters.json').write_text(json.dumps(data,indent=2)+'\n')
