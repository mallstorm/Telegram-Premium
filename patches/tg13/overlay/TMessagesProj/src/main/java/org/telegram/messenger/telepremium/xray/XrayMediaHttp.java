package org.telegram.messenger.telepremium.xray;

import java.net.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.telegram.messenger.telepremium.xray.http.OkHttpDataSource;

/** Fresh client per player: an earlier direct connection cannot survive a route change. */
public final class XrayMediaHttp {
    public static androidx.media3.datasource.DataSource.Factory factory(){
        OkHttpClient client=new OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
                .proxySelector(new ProxySelector(){
                    @Override public List<Proxy> select(URI uri){
                        try{return Collections.singletonList(XrayRuntime.mediaProxy());}
                        catch(IOException e){return Collections.singletonList(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",9)));}
                    }
                    @Override public void connectFailed(URI uri,SocketAddress address,IOException error){}
                }).build();
        return new OkHttpDataSource.Factory(client).setUserAgent("Mozilla/5.0 (X11; Linux x86_64; rv:10.0) Gecko/20150101 Firefox/47.0 (Chrome)");
    }
}
