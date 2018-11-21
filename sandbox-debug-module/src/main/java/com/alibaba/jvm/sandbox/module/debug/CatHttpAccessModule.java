package com.alibaba.jvm.sandbox.module.debug;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.LoadCompleted;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.listener.Sentry;
import com.alibaba.jvm.sandbox.api.listener.ext.Advice;
import com.alibaba.jvm.sandbox.api.listener.ext.AdviceListener;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import com.dianping.cat.Cat;
import com.dianping.cat.CatConstants;
import com.dianping.cat.configuration.client.entity.Server;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.internal.DefaultMessageManager;
import com.dianping.cat.message.internal.DefaultTransaction;
import com.dianping.cat.servlet.MonitorResponse;
import com.dianping.cat.status.http.HttpStats;
import com.dianping.cat.util.Joiners;
import com.dianping.cat.util.UrlParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.InvocationTargetException;
import java.util.*;


/**
 * 基于HTTP-SERVLET(v2.4)规范的HTTP访问日志
 *
 * @author luanjia@taobao.com
 */
@MetaInfServices(Module.class)
@Information(id = "cat-http-access", version = "0.0.1", author = "yuanyue@staff.hexun.com")
public class CatHttpAccessModule implements Module, LoadCompleted {

    private final Logger stLogger = LoggerFactory.getLogger(this.getClass());

    @Resource
    private ModuleEventWatcher moduleEventWatcher;


    private static Set<String> excludeUrls = new HashSet<String>();

    private static Set<String> excludePrefixes = new HashSet<String>();

    static {
        excludeUrls.add("favicon.ico");
    }

    /**
     * HTTP处理步骤
     * {@code
     * HttpServlet.service():BEGIN
     * -> HttpServletResponse.[setState/sendError]():BEGIN
     * -> HttpServletResponse.[setState/sendError]():FINISH
     * -> HttpServlet.service():FINISH
     * }
     */
//    enum HttpProcessStep {
//        waitingHttpServletServiceBegin,
//        waitingHttpServletResponseState,
//        waitingHttpServletServiceFinish
//    }

    //    /**
//     * HTTP接入信息
//     */
    class HttpAccess {
        Transaction transaction;
        int status;
        long beginTimestamp;
//        MonitorResponse res;
    }

    // 安排一个哨兵，用于观察Servlet执行步骤
//    private final Sentry<HttpProcessStep> sentry = new Sentry<HttpProcessStep>(waitingHttpServletServiceBegin);

    @Override
    public void loadCompleted() {
//        buildingHttpStatusFillBack();
        buildingHttpServletService();
    }

//    /*
//     * HTTP状态码回填
//     * 因为在3.0之前你都很难拿到HTTP的应答状态，必须拦截HttpServletResponse的setStatus/sendError才能拿到
//     * 而且还必须要考虑到200这种状态码为默认状态码的情况
//     */
//    private void buildingHttpStatusFillBack() {
//        new EventWatchBuilder(moduleEventWatcher)
//                .onClass("javax.servlet.http.HttpServletResponse")
//                /**/.includeSubClasses()
//                .onBehavior("setStatus")
//                /**/.withParameterTypes(int.class)
//                .onBehavior("sendError")
//                /**/.withParameterTypes(int.class)
//                /**/.withParameterTypes(int.class, String.class)
//                .onWatch(new AdviceListener() {
//                    @Override
//                    public void before(Advice advice) {
//
//                    }
//                });
//    }

    /*
     * 拦截HttpServlet的服务请求入口
     */
    private void buildingHttpServletService() {
        new EventWatchBuilder(moduleEventWatcher)
                .onClass("javax.servlet.http.HttpServlet")
                .includeSubClasses()
                .includeBootstrap()
                .onBehavior("service")
                .withParameterTypes(
                        "javax.servlet.http.HttpServletRequest",
                        "javax.servlet.http.HttpServletResponse"
                )
                .onWatch(new AdviceListener() {

                    final String MARK_HTTP_BEGIN = "MARK_HTTP_BEGIN";

                    private String servers;


                    /**
                     * 排除的uri
                     * @param uri
                     * @return
                     */
                    private boolean excludeURI(String uri) {
                        try {
                            boolean exclude = excludeUrls.contains(uri);

                            if (!exclude) {
                                for (String prefix : excludePrefixes) {
                                    if (uri.startsWith(prefix)) {
                                        exclude = true;
                                        break;
                                    }
                                }
                            }
                            return exclude;
                        } catch (Exception e) {
                            return false;
                        }
                    }

                    private String getCatServer() {
                        try {
                            if (servers == null) {
                                DefaultMessageManager manager = (DefaultMessageManager) Cat.getManager();
                                List<Server> servers = manager.getConfigService().getServers();

                                this.servers = Joiners.by(',').join(servers, new Joiners.IBuilder<Server>() {
                                    @Override
                                    public String asString(Server server) {
                                        String ip = server.getIp();
                                        int httpPort = server.getHttpPort();

                                        return ip + ":" + httpPort;
                                    }
                                });
                            }

                            return servers;
                        } catch (Exception e) {
                            return null;
                        }
                    }


                    private void customizeStatus(Transaction t, Object req) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
                        Object catStatus = invokeMethod(req, "getAttribute", CatConstants.CAT_STATE);

                        if (catStatus != null) {
                            t.setStatus(catStatus.toString());
                        } else {
                            t.setStatus(Message.SUCCESS);
                        }
                    }

                    private void customizeUri(Transaction t, Object req) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
                        if (t instanceof DefaultTransaction) {
                            Object catPageType = invokeMethod(req, "getAttribute", CatConstants.CAT_PAGE_TYPE);

                            if (catPageType instanceof String) {
                                ((DefaultTransaction) t).setType(catPageType.toString());
                            }

                            Object catPageUri = invokeMethod(req, "getAttribute", CatConstants.CAT_PAGE_URI);

                            if (catPageUri instanceof String) {
                                ((DefaultTransaction) t).setName(catPageUri.toString());
                            }
                        }
                    }

                    private void logCatMessageId(Object res) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
                        boolean isTraceMode = Cat.getManager().isTraceMode();

                        if (isTraceMode) {
                            String id = Cat.getCurrentMessageId();
                            invokeMethod(res, "setHeader", "X-CAT-ROOT-ID", id);
                            invokeMethod(res, "setHeader", "X-CAT-SERVER", getCatServer());
                        }
                    }


                    private void logPayload(Object req, boolean top, String type) {
                        try {
                            if (top) {
                                logRequestClientInfo(req, type);
                                logRequestPayload(req, type);
                            } else {
                                logRequestPayload(req, type);
                            }
                        } catch (Exception e) {
                            Cat.logError(e);
                        }
                    }

                    private void logRequestClientInfo(Object req, String type) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
                        StringBuilder sb = new StringBuilder(1024);
                        String ip = "";
                        String ipForwarded = invokeMethod(req, "getHeader", "x-forwarded-for");

                        if (ipForwarded == null) {
                            ip = invokeMethod(req, "getRemoteAddr");
                        } else {
                            ip = ipForwarded;
                        }

                        sb.append("IPS=").append(ip);
                        sb.append("&VirtualIP=").append(invokeMethod(req, "getRemoteAddr"));
                        sb.append("&Server=").append(invokeMethod(req, "getServerName"));
                        sb.append("&Referer=").append(invokeMethod(req, "getHeader", "referer"));
                        sb.append("&Agent=").append(invokeMethod(req, "getHeader", "user-agent"));

                        Cat.logEvent(type, type + ".Server", Message.SUCCESS, sb.toString());
                    }

                    private void logRequestPayload(Object req, String type) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
                        StringBuilder sb = new StringBuilder(256);
                        String scheme = invokeMethod(req, "getScheme");
                        sb.append(scheme.toUpperCase()).append('/');
                        sb.append(invokeMethod(req, "getMethod")).append(' ').append(invokeMethod(req, "getRequestURI"));

                        String qs = invokeMethod(req, "getQueryString");

                        if (qs != null) {
                            sb.append('?').append(qs);
                        }

                        Cat.logEvent(type, type + ".Method", Message.SUCCESS, sb.toString());
                    }

                    private void logTraceMode(Object req) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
                        String traceMode = "X-CAT-TRACE-MODE";
                        String headMode = invokeMethod(req, "getHeader", traceMode);

                        if ("true".equals(headMode)) {
                            Cat.getManager().setTraceMode(true);
                        }
                    }


                    @Override
                    public void before(Advice advice) throws Throwable {
                        final Object req = advice.getParameterArray()[0];
                        final Object response = advice.getParameterArray()[1];
                        String uri = invokeMethod(req, "getRequestURI");
                        if (excludeURI(uri))
                            return;

                        Message message = Cat.getManager().getThreadLocalMessageTree().getMessage();
                        boolean top = message == null;
                        String type;


                        if (top) {
                            type = CatConstants.TYPE_URL;
                            logTraceMode(req);
                        } else {
                            type = CatConstants.TYPE_URL_FORWARD;
                        }

                        HttpAccess ha = new HttpAccess();
                        ha.status = 0;

                        Transaction t = Cat.newTransaction(type, UrlParser.format(uri));

                        logPayload(req, top, type);
                        logCatMessageId(response);

                        ha.transaction = t;
                        ha.beginTimestamp = System.currentTimeMillis();

                        advice.mark(MARK_HTTP_BEGIN);
                        advice.attach(ha);
                    }

                    @Override
                    public void afterReturning(Advice advice) {
                        finishing(advice);
                    }

                    @Override
                    public void afterThrowing(Advice advice) {
                        finishing(advice);
                    }

                    /**
                     * 判断是否请求对称结束
                     *
                     * @param advice 通知
                     * @return TRUE:对称结束;FALSE:非对称结束
                     */
                    private boolean finishing(Advice advice) {
                        HttpAccess ha = advice.attachment();
                        final Object req = advice.getParameterArray()[0];
                        final Object response = advice.getParameterArray()[1];
                        try {
                            customizeStatus(ha.transaction, req);
                            if (advice.getThrowable() != null) {
                                ha.transaction.setStatus(advice.getThrowable());
                            }
                        } catch (Throwable e) {
                            ha.status = 500;
                            ha.transaction.setStatus(e);
                            Cat.logError(e);
                        } finally {
                            try {
                                customizeUri(ha.transaction, req);
                                ha.transaction.complete();
                                int status = invokeMethod(response, "getStatus");
                                HttpStats.currentStatsHolder().doRequestStats(System.currentTimeMillis() - ha.beginTimestamp, ha.status > 0 ? ha.status : status);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        return true;
                    }

                });
    }

    static {
        Cat.initializeByDomainForce("cat111");
    }

    /*
     * 泛型转换方法调用
     * 底层使用apache common实现
     */
    private static <T> T invokeMethod(final Object object,
                                      final String methodName,
                                      final Object... args) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return (T) MethodUtils.invokeMethod(object, methodName, args);
    }

}