"""A stand-in for wpa_supplicant's control socket (UNIX datagram), with just the commands wfdd uses."""
import os
import socket
import threading


class FakeWpa:
    def __init__(self, directory):
        self.path = os.path.join(directory, 'p2p0')
        self.log = []
        self.wifi_display = '0'
        self.subelem = ''
        self.device_name = 'Android_test'
        self.ext_listen = None
        self.refuse = set()          # command prefixes answered with FAIL
        self.silent = False          # answer nothing at all
        self._sock = None
        self._thread = None
        self._stop = False

    def start(self):
        self._sock = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self._sock.bind(self.path)
        self._sock.settimeout(0.2)
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self._stop = True
        if self._thread: self._thread.join(2)
        if self._sock: self._sock.close()
        try: os.unlink(self.path)
        except OSError: pass

    def reset_state(self):
        """What a restart of wpa_supplicant does: everything the daemon set is gone."""
        self.wifi_display, self.subelem, self.ext_listen = '0', '', None

    def _run(self):
        while not self._stop:
            try: data, addr = self._sock.recvfrom(4096)
            except socket.timeout: continue
            except OSError: return
            command = data.decode(errors='replace')
            self.log.append(command)
            if self.silent or not addr: continue
            reply = self._answer(command)
            try: self._sock.sendto(reply.encode(), addr)
            except OSError: pass

    def _answer(self, command):
        if any(command.startswith(p) for p in self.refuse): return 'FAIL\n'
        if command.startswith('SET wifi_display '):
            self.wifi_display = command.split()[-1]; return 'OK\n'
        if command == 'GET wifi_display': return self.wifi_display + '\n'
        if command.startswith('WFD_SUBELEM_SET 0 '):
            self.subelem = command.split()[-1]; return 'OK\n'
        if command == 'WFD_SUBELEM_GET 0': return self.subelem + '\n'
        if command.startswith('SET device_name '):
            self.device_name = command[len('SET device_name '):]; return 'OK\n'
        if command == 'P2P_EXT_LISTEN':
            self.ext_listen = None; return 'OK\n'
        if command.startswith('P2P_EXT_LISTEN '):
            self.ext_listen = tuple(int(x) for x in command.split()[1:]); return 'OK\n'
        if command == 'P2P_GROUP_REMOVE *': return 'OK\n'
        return 'UNKNOWN COMMAND\n'
