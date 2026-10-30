"""Temporary app-UID firewall for offline tests over wireless ADB; never alters Wi-Fi or system files."""
import re
import shlex
import subprocess

class AppNetworkBlock:
    def __init__(self,serial,app='moe.kiwi.thinkos'):
        self.base=['adb','-s',serial];self.app=app;self.installed=[];self.uid=None
    def shell(self,*values):
        return subprocess.check_output(self.base+['shell','-T',shlex.join(map(str,values))],stderr=subprocess.STDOUT,timeout=25)
    def start(self):
        info=self.shell('dumpsys','package',self.app).decode()
        self.uid=int(re.search(r'\buserId=(\d+)',info)[1])
        if self.uid<10000:raise ValueError('Refusing to block a system UID')
        try:
            for program in ('iptables','ip6tables'):
                self.shell('su','-c',shlex.join([program,'-I','OUTPUT','1','-m','owner','--uid-owner',str(self.uid),'-j','REJECT']))
                self.installed.append(program)
        except BaseException:self.stop();raise
        return self
    def evidence(self):
        return '\n'.join(self.shell('su','-c',p+' -L OUTPUT -n -v').decode() for p in self.installed)
    def stop(self):
        errors=[]
        for p in self.installed[:]:
            try:
                self.shell('su','-c',shlex.join([p,'-D','OUTPUT','-m','owner','--uid-owner',str(self.uid),'-j','REJECT']))
                self.installed.remove(p)
            except Exception as e:errors.append(str(e))
        if errors:raise RuntimeError('Firewall cleanup failed: '+'; '.join(errors))
    def __enter__(self):return self.start()
    def __exit__(self,*args):self.stop()
