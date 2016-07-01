package net.dubboclub.catmonitor.registry;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.registry.RegistryFactory;
import net.dubboclub.catmonitor.constants.CatConstants;

import java.util.List;

/**
 * 注册中 自动Wrap扩展点的Wrapper类
 * 
 */
public class CatRegistryFactoryWrapper implements RegistryFactory {
    private RegistryFactory registryFactory;

    public CatRegistryFactoryWrapper(RegistryFactory registryFactory) {
        this.registryFactory = registryFactory;
    }

    @Override
    public Registry getRegistry(URL url) {
        return new RegistryWrapper(registryFactory.getRegistry(url));
    }

    class RegistryWrapper implements Registry {
        private Registry originRegistry;
        
        /**
         * 消费者获取的是appliction是自己的application名称，com.alibaba.dubbo.rpc.cluster.support.ClusterUtils.mergeUrl(URL, Map<String, String>)
         * 冗余了一个服务端application名称
         * @param url
         * @return URL
         */
        private URL appendProviderAppName(URL url){
            String side = url.getParameter(Constants.SIDE_KEY);
            if(Constants.PROVIDER_SIDE.equals(side)){
                url=url.addParameter(CatConstants.PROVIDER_APPLICATION_NAME,url.getParameter(Constants.APPLICATION_KEY));
            }
            return url;
        }

        public RegistryWrapper(Registry originRegistry) {
            this.originRegistry = originRegistry;
        }

        @Override
        public URL getUrl() {
            return originRegistry.getUrl();
        }

        @Override
        public boolean isAvailable() {
            return originRegistry.isAvailable();
        }

        @Override
        public void destroy() {
            originRegistry.destroy();
        }

        @Override
        public void register(URL url) {
            originRegistry.register(appendProviderAppName(url));
        }

        @Override
        public void unregister(URL url) {
            originRegistry.unregister(appendProviderAppName(url));
        }

        @Override
        public void subscribe(URL url, NotifyListener listener) {
            originRegistry.subscribe(url,listener);
        }

        @Override
        public void unsubscribe(URL url, NotifyListener listener) {
            originRegistry.unsubscribe(url,listener);
        }

        @Override
        public List<URL> lookup(URL url) {
            return originRegistry.lookup(appendProviderAppName(url));
        }
    }
}
