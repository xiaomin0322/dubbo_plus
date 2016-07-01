package net.dubboclub.catmonitor;

import java.util.HashMap;
import java.util.Map;

import net.dubboclub.catmonitor.constants.CatConstants;

import org.apache.commons.lang.StringUtils;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.dianping.cat.Cat;
import com.dianping.cat.message.Event;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.internal.AbstractMessage;

/**
 * Created by bieber on 2015/11/4.
 */
@Activate(group = {Constants.PROVIDER, Constants.CONSUMER},order = -9000)
public class CatTransaction implements Filter {
	
    private final static String DUBBO_BIZ_ERROR="DUBBO_BIZ_ERROR";

    private final static String DUBBO_TIMEOUT_ERROR="DUBBO_TIMEOUT_ERROR";
    
    private final static String DUBBO_REMOTING_ERROR="DUBBO_REMOTING_ERROR";


    private static final ThreadLocal<Cat.Context> CAT_CONTEXT = new ThreadLocal<Cat.Context>();

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
    	System.out.println("呵呵我来了================================================================");
        if(!DubboCat.isEnable()){
            Result result =  invoker.invoke(invocation);
            return result;
        }
        URL url = invoker.getUrl();
        //客户端,服务端标志
        String sideKey = url.getParameter(Constants.SIDE_KEY);
        //接口名称。方法名称
        String loggerName = invoker.getInterface().getSimpleName()+"."+invocation.getMethodName();
        //Transaction 客户端类型 默认是 PigeonCall 服务端默认是 PigeonService
        String type = CatConstants.CROSS_CONSUMER;
        if(Constants.PROVIDER_SIDE.equals(sideKey)){
            type= CatConstants.CROSS_SERVER;
        }
        System.out.println("url=="+url);
        System.out.println("sideKey=="+sideKey);
        System.out.println("loggerName=="+loggerName);
        System.out.println("type=="+type);
       
        //构建Transaction
        Transaction transaction = Cat.newTransaction(type,loggerName);
        Result result=null;
        try{
        	//初始化Context
            Cat.Context context = getContext();
            //如果是客户端
            if(Constants.CONSUMER_SIDE.equals(sideKey)){
                createConsumerCross(url,transaction);
                Cat.logRemoteCallClient(context);
            }else{
                createProviderCross(url,transaction);
                Cat.logRemoteCallServer(context);
            }
            //将context 设置到 RpcContext
            setAttachment(context);
            
            System.out.println("ROOT="+context.getProperty(context.ROOT)+" PARENT="+context.getProperty(context.PARENT)+" CHILD="+context.getProperty(context.CHILD));
            
            //执行方法
            result =  invoker.invoke(invocation);

            
            System.out.println("transaction=="+transaction);
            
             //如果有异常
            if(result.hasException()){
                //给调用接口出现异常进行打点
                Throwable throwable = result.getException();
                Event event = null;
                if(RpcException.class==throwable.getClass()){
                    Throwable caseBy = throwable.getCause();
                    if(caseBy!=null&&caseBy.getClass()==TimeoutException.class){
                        event = Cat.newEvent(DUBBO_TIMEOUT_ERROR,loggerName);
                    }else{
                        event = Cat.newEvent(DUBBO_REMOTING_ERROR,loggerName);
                    }
                }else if(RemotingException.class.isAssignableFrom(throwable.getClass())){
                    event = Cat.newEvent(DUBBO_REMOTING_ERROR,loggerName);
                }else{
                    event = Cat.newEvent(DUBBO_BIZ_ERROR,loggerName);
                }
                event.setStatus(result.getException());
                completeEvent(event);
                transaction.addChild(event);
                transaction.setStatus(result.getException().getClass().getSimpleName());
            }else{
                transaction.setStatus(Message.SUCCESS);
            }
            return result;
        }catch (RuntimeException e){
            Event event = null;
            if(RpcException.class==e.getClass()){
                Throwable caseBy = e.getCause();
                if(caseBy!=null&&caseBy.getClass()==TimeoutException.class){
                    event = Cat.newEvent(DUBBO_TIMEOUT_ERROR,loggerName);
                }else{
                    event = Cat.newEvent(DUBBO_REMOTING_ERROR,loggerName);
                }
            }else{
                event = Cat.newEvent(DUBBO_BIZ_ERROR,loggerName);
            }
            event.setStatus(e);
            completeEvent(event);
            transaction.addChild(event);
            transaction.setStatus(e.getClass().getSimpleName());
            if(result==null){
                throw e;
            }else{
                return result;
            }
        }finally {
            transaction.complete();
            CAT_CONTEXT.remove();
        }
    }

    static class DubboCatContext implements Cat.Context{

        private Map<String,String> properties = new HashMap<String, String>();

        @Override
        public void addProperty(String key, String value) {
            properties.put(key,value);
        }

        @Override
        public String getProperty(String key) {
            return properties.get(key);
        }
    }

    private String getProviderAppName(URL url){
        String appName = url.getParameter(CatConstants.PROVIDER_APPLICATION_NAME);
        if(StringUtils.isEmpty(appName)){
            String interfaceName  = url.getParameter(Constants.INTERFACE_KEY);
            appName = interfaceName.substring(0,interfaceName.lastIndexOf('.'));
        }
        return appName;
    }

    /**
     * 将context 设置到 RpcContext
     * @param context
     */
    private void setAttachment(Cat.Context context){
        RpcContext.getContext().setAttachment(Cat.Context.ROOT,context.getProperty(Cat.Context.ROOT));
        RpcContext.getContext().setAttachment(Cat.Context.CHILD,context.getProperty(Cat.Context.CHILD));
        RpcContext.getContext().setAttachment(Cat.Context.PARENT,context.getProperty(Cat.Context.PARENT));
    }

    private Cat.Context getContext(){
        Cat.Context context = CAT_CONTEXT.get();
        if(context==null){
            context = initContext();
            CAT_CONTEXT.set(context);
        }
        return context;
    }

    private Cat.Context initContext(){
        Cat.Context context = new DubboCatContext();
        Map<String,String> attachments = RpcContext.getContext().getAttachments();
        if(attachments!=null&&attachments.size()>0){
            for(Map.Entry<String,String> entry:attachments.entrySet()){
                if(Cat.Context.CHILD.equals(entry.getKey())||Cat.Context.ROOT.equals(entry.getKey())||Cat.Context.PARENT.equals(entry.getKey())){
                    context.addProperty(entry.getKey(),entry.getValue());
                }
            }
        }
        return context;
    }

    /**
     * 客户端处理方法
     * @param url
     * @param transaction
     */
    private void createConsumerCross(URL url,Transaction transaction){
    	//供应者的名称 如：com.cmall.goods.rpc
        Event crossAppEvent =   Cat.newEvent(CatConstants.CONSUMER_CALL_APP,getProviderAppName(url));
        //供应者ip
        Event crossServerEvent =   Cat.newEvent(CatConstants.CONSUMER_CALL_SERVER,url.getHost());
        //供应者端口
        Event crossPortEvent =   Cat.newEvent(CatConstants.CONSUMER_CALL_PORT,url.getPort()+"");
        crossAppEvent.setStatus(Event.SUCCESS);
        crossServerEvent.setStatus(Event.SUCCESS);
        crossPortEvent.setStatus(Event.SUCCESS);
        completeEvent(crossAppEvent);
        completeEvent(crossPortEvent);
        completeEvent(crossServerEvent);
        transaction.addChild(crossAppEvent);
        transaction.addChild(crossPortEvent);
        transaction.addChild(crossServerEvent);
    }

    private void completeEvent(Event event){
        AbstractMessage message = (AbstractMessage) event;
        message.setCompleted(true);
    }

    /**
     * 服务端处理方法
     * @param url
     * @param transaction
     */
    private void createProviderCross(URL url,Transaction transaction){
    	//供应者名称
        String consumerAppName = RpcContext.getContext().getAttachment(Constants.APPLICATION_KEY);
        if(StringUtils.isEmpty(consumerAppName)){
            consumerAppName= RpcContext.getContext().getRemoteHost()+":"+ RpcContext.getContext().getRemotePort();
        }
        //供应者名称
        Event crossAppEvent = Cat.newEvent(CatConstants.PROVIDER_CALL_APP,consumerAppName);
        //供应者地址
        Event crossServerEvent = Cat.newEvent(CatConstants.PROVIDER_CALL_SERVER,url.getHost());
        crossAppEvent.setStatus(Event.SUCCESS);
        crossServerEvent.setStatus(Event.SUCCESS);
        completeEvent(crossAppEvent);
        completeEvent(crossServerEvent);
        transaction.addChild(crossAppEvent);
        transaction.addChild(crossServerEvent);
    }
}
