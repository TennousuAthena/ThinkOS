"""Temporary app-UID firewall for offline tests over wireless ADB; never alters Wi-Fi or system files."""
import re
import shlex
import subprocess

class AppNetworkBlock:
    def __init__(self,serial,app='moe.kiwi.thinkos',*,loopback_port=None):
        self.base=['adb','-s',serial];self.app=app;self.installed=[];self.uid=None
        if loopback_port is not None and (type(loopback_port) is not int or not 1 <= loopback_port <= 65535):
            raise ValueError('Invalid local fixture port')
        self.loopback_port=loopback_port
    def shell(self,*values):
        return subprocess.check_output(self.base+['shell','-T',shlex.join(map(str,values))],stderr=subprocess.STDOUT,timeout=25)
    def start(self):
        info=self.shell('dumpsys','package',self.app).decode()
        self.uid=int(re.search(r'\buserId=(\d+)',info)[1])
        if self.uid<10000:raise ValueError('Refusing to block a system UID')
        owner=['-m','owner','--uid-owner',str(self.uid)]
        rules=[('iptables',owner+['-j','REJECT']),('ip6tables',owner+['-j','REJECT'])]
        if self.loopback_port is not None:
            rules.append(('iptables',owner+['-d','127.0.0.1/32','-p','tcp','--dport',str(self.loopback_port),'-j','ACCEPT']))
        try:
            for program,rule in rules:
                self.shell('su','-c',shlex.join([program,'-I','OUTPUT','1',*rule]))
                self.installed.append((program,rule))
        except BaseException:self.stop();raise
        return self
    def evidence(self):
        return '\n'.join(self.shell('su','-c',p+' -L OUTPUT -n -v').decode() for p in dict(self.installed))
    def stop(self):
        errors=[]
        for p,rule in reversed(self.installed[:]):
            try:
                self.shell('su','-c',shlex.join([p,'-D','OUTPUT',*rule]))
                self.installed.remove((p,rule))
            except Exception as e:errors.append(str(e))
        if errors:raise RuntimeError('Firewall cleanup failed: '+'; '.join(errors))
    def __enter__(self):return self.start()
    def __exit__(self,*args):self.stop()
