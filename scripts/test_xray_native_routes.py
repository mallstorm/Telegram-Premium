#!/usr/bin/env python3
"""Exercise the official pinned Linux companion of the bundled ARM64 core on loopback only.
No real proxy accounts, Telegram sessions or external destination traffic are used.
"""
from pathlib import Path
import argparse,ctypes,concurrent.futures,hashlib,http.client,http.server,json,select,socket,socketserver,subprocess,tempfile,threading,urllib.request,zipfile
from test_xray_config_and_sync_model import compile_test
URL='https://github.com/XTLS/libXray/releases/download/v26.10.31/libxray-linux-x64.zip'
SHA='27b9afad1c5ad4817c8fade6861f706906d2447f6a20090a69dccfdf3959b1ff'
class Target(http.server.BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1';hits=0;lock=threading.Lock()
    def do_GET(self):
        with Target.lock:Target.hits+=1
        body=json.dumps({'source':self.client_address[0],'path':self.path}).encode();self.send_response(200);self.send_header('Content-Length',str(len(body)));self.send_header('Connection','close');self.end_headers();self.wfile.write(body)
    def log_message(self,*args):pass
class Socks(socketserver.BaseRequestHandler):
    def handle(self):
        def read(n):
            out=b''
            while len(out)<n:
                data=self.request.recv(n-len(out))
                if not data:raise EOFError()
                out+=data
            return out
        remote=None
        try:
            self.request.settimeout(5);version,count=read(2);assert version==5;methods=read(count);assert 0 in methods;self.request.sendall(b'\x05\x00')
            version,cmd,_,kind=read(4);assert version==5 and cmd==1
            if kind==1:host=socket.inet_ntoa(read(4))
            elif kind==3:host=read(read(1)[0]).decode()
            elif kind==4:host=socket.inet_ntop(socket.AF_INET6,read(16))
            else:raise AssertionError('Unknown address kind')
            port=int.from_bytes(read(2),'big');assert host=='127.0.0.1' and port==self.server.target_port,'Test must never connect outside loopback'
            remote=socket.create_connection((host,port),timeout=5,source_address=(self.server.source_ip,0));self.request.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
            while True:
                ready,_,_=select.select([self.request,remote],[],[],5)
                if not ready:return
                for source in ready:
                    data=source.recv(65536)
                    if not data:return
                    (remote if source is self.request else self.request).sendall(data)
        except (EOFError,ConnectionError,OSError):pass
        finally:
            if remote:remote.close()
class ProxyServer(socketserver.ThreadingTCPServer):allow_reuse_address=True;daemon_threads=True
class Test:
    def __init__(self,library,classes):
        self.lib=ctypes.CDLL(str(library));self.lib.CGoInvoke.argtypes=[ctypes.c_char_p];self.lib.CGoInvoke.restype=ctypes.c_void_p;self.lib.CGoFree.argtypes=[ctypes.c_void_p];self.checks=0;self.classes=classes
    def check(self,condition,message):
        if not condition:raise AssertionError(message)
        self.checks+=1
    def invoke(self,method,payload=None):
        pointer=self.lib.CGoInvoke(json.dumps({'apiVersion':3,'method':method,'payload':payload or {}}).encode());assert pointer
        try:result=json.loads(ctypes.string_at(pointer))
        finally:self.lib.CGoFree(pointer)
        assert result.get('success'),(method,result.get('error'));return result.get('data')
    def config(self,profiles):
        raw=subprocess.check_output(['java','-cp',str(self.classes),'XrayAndSyncModelTest',json.dumps({'profiles':profiles})],text=True);return json.loads(raw)
    def fetch(self,port,target,path):
        connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
        try:
            connection.request('GET',f'http://127.0.0.1:{target}/{path}');response=connection.getresponse();body=response.read();return response.status,json.loads(body)
        finally:connection.close()
    def closed(self,port):
        try:
            s=socket.create_connection(('127.0.0.1',port),timeout=.5);s.close();return False
        except OSError:return True
    def run(self):
        target=http.server.ThreadingHTTPServer(('127.0.0.1',0),Target);threading.Thread(target=target.serve_forever,daemon=True).start();servers=[]
        try:
            for ip in ['127.0.0.2','127.0.0.3']:
                server=ProxyServer(('127.0.0.1',0),Socks);server.source_ip=ip;server.target_port=target.server_port;threading.Thread(target=server.serve_forever,daemon=True).start();servers.append(server)
            self.check('26.10.31' in json.dumps(self.invoke('xrayVersion')),'native version must match bundled release')
            imported=self.invoke('convertShareLinksToXrayJson',{'text':'socks://127.0.0.1:1080#Imported\nvless://00000000-0000-0000-0000-000000000001@127.0.0.1:443?encryption=none&security=tls&type=tcp#TLS'})['outbounds']
            self.check(len(imported)==2 and [n['protocol'] for n in imported]==['socks','vless'],'official share-link converter imports multiple distinct protocol profiles')
            ports=self.invoke('getFreePorts',{'count':4})['ports'];self.check(len(set(ports))==4,'native allocator returns four distinct listeners')
            profiles=[{'id':'account-a','server':servers[0].server_address[1],'socks':ports[0],'http':ports[1]},{'id':'account-b','server':servers[1].server_address[1],'socks':ports[2],'http':ports[3]}]
            cfg=self.config(profiles)
            candidate=json.loads(json.dumps(cfg));candidate['outbounds'][2]=dict(imported[0],tag='account-a');candidate['outbounds'][3]=dict(imported[1],tag='account-b');self.invoke('testXray',{'xrayJson':json.dumps(candidate)})
            self.check(True,'converted profiles validate when normalized into independent application routes')
            self.invoke('testXray',{'xrayJson':json.dumps(cfg)});self.check(all(self.closed(p) for p in ports),'validation alone does not start listeners')
            invalid=json.loads(json.dumps(cfg));invalid['outbounds'][2]['protocol']='not-a-protocol';rejected=False
            try:self.invoke('testXray',{'xrayJson':json.dumps(invalid)})
            except AssertionError:rejected=True
            self.check(rejected and all(self.closed(p) for p in ports),'invalid configuration is rejected before any listener starts')
            self.invoke('runXray',{'xrayJson':json.dumps(cfg)})
            live_rejected=False
            try:self.invoke('testXray',{'xrayJson':json.dumps(cfg)})
            except AssertionError:live_rejected=True
            self.check(live_rejected and self.fetch(ports[3],target.server_port,'still-live')[1]['source']=='127.0.0.3','isolated-only native validation must be skipped on a running managed core')
            status,a=self.fetch(ports[1],target.server_port,'account-a');self.check(status==200 and a['source']=='127.0.0.2','first profile must use only its chosen outbound')
            status,b=self.fetch(ports[3],target.server_port,'account-b');self.check(status==200 and b['source']=='127.0.0.3','second account/media route must use a different outbound')
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                jobs=[pool.submit(self.fetch,ports[1] if i%2==0 else ports[3],target.server_port,'parallel-'+str(i)) for i in range(16)]
                for i,job in enumerate(jobs):status,value=job.result();self.check(status==200 and value['source']==('127.0.0.2' if i%2==0 else '127.0.0.3'),'simultaneous routes remain isolated '+str(i))
            servers[1].shutdown();servers[1].server_close();before=Target.hits;failed=False
            try:status,value=self.fetch(ports[3],target.server_port,'unavailable');failed=status!=200
            except (OSError,ValueError,http.client.HTTPException):failed=True
            self.check(failed and Target.hits==before,'an unavailable selected outbound never falls back to direct/another profile')
            self.check(self.fetch(ports[1],target.server_port,'still-works')[1]['source']=='127.0.0.2','other profile stays operational')
            self.invoke('stopXray');self.check(all(self.closed(p) for p in ports),'stopping core closes every listener')
            cfg['routing']['rules']=[];self.invoke('runXray',{'xrayJson':json.dumps(cfg)});before=Target.hits;failed=False
            try:status,value=self.fetch(ports[1],target.server_port,'unmatched');failed=status!=200
            except (OSError,ValueError,http.client.HTTPException):failed=True
            self.check(failed and Target.hits==before,'unmatched inbound traffic hits the block outbound, not freedom')
            self.invoke('stopXray')
            ports=self.invoke('getFreePorts',{'count':20})['ports'];profiles=[{'id':'max-'+str(i),'server':servers[0].server_address[1],'socks':ports[i*2],'http':ports[i*2+1]} for i in range(10)];cfg=self.config(profiles)
            self.invoke('runXray',{'xrayJson':json.dumps(cfg)});self.check(all(not self.closed(p) for p in ports),'ten profiles bind twenty independent loopback listeners')
            self.check(self.fetch(ports[19],target.server_port,'tenth-profile')[1]['source']=='127.0.0.2','profile ten can carry traffic')
            self.invoke('stopXray');self.check(all(self.closed(p) for p in ports),'ten-profile shutdown releases all listeners')
            print('XrayNativeRoutesTest OK:',self.checks,'checks (official v26.10.31, loopback only)')
        finally:
            try:self.invoke('stopXray')
            except Exception:pass
            for server in servers:
                try:server.shutdown();server.server_close()
                except Exception:pass
            target.shutdown();target.server_close()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--library',type=Path);parser.add_argument('--download',action='store_true');args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='tp-xray-native-') as folder:
        root=Path(folder);library=args.library
        if args.download:
            archive=root/'core.zip'
            with urllib.request.urlopen(URL,timeout=180) as response,archive.open('wb') as out:
                while data:=response.read(1024*1024):out.write(data)
            assert hashlib.sha256(archive.read_bytes()).hexdigest()==SHA,'Pinned native artifact mismatch'
            with zipfile.ZipFile(archive) as zip:zip.extract('libxray-linux-x64/libXray.so',root)
            library=root/'libxray-linux-x64/libXray.so'
        if library is None:parser.error('Pass --library or --download')
        classes=compile_test(root);Test(library.resolve(),classes).run()
if __name__=='__main__':main()
