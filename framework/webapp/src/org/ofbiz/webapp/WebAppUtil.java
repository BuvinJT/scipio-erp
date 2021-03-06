/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.webapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.tomcat.util.descriptor.DigesterFactory;
import org.apache.tomcat.util.descriptor.web.ServletDef;
import org.apache.tomcat.util.descriptor.web.WebRuleSet;
import org.apache.tomcat.util.descriptor.web.WebXml;
import org.apache.tomcat.util.digester.Digester;
import org.ofbiz.base.component.ComponentConfig;
import org.ofbiz.base.component.ComponentConfig.WebappInfo;
import org.ofbiz.base.util.Assert;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilXml.LocalErrorHandler;
import org.ofbiz.base.util.UtilXml.LocalResolver;
import org.ofbiz.base.util.cache.UtilCache;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.webapp.control.ControlServlet;
import org.ofbiz.webapp.control.ServletUtil;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Web application utilities.
 * <p>This class reuses some of the Tomcat/Catalina classes for convenience, but
 * OFBiz does not need to be running on Tomcat for this to work.</p>
 */
public final class WebAppUtil {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());
    private static final String webAppFileName = "/WEB-INF/web.xml";
    private static final UtilCache<String, WebXml> webXmlCache = UtilCache.createUtilCache("webapp.WebXml");

    /**
     * SCIPIO: Fast, light homemache cache to optimize control servlet path lookups.
     */
    private static final Map<String, String> controlServletPathWebappInfoCache = new ConcurrentHashMap<String, String>();
    
    /**
     * SCIPIO: Fast, light homemache cache to optimize WebappInfo lookups by webSiteId.
     */
    private static final Map<String, WebappInfo> webappInfoWebSiteIdCache = new ConcurrentHashMap<String, WebappInfo>();

    private static final Pattern urlQueryDelimPat = Pattern.compile("[?;#&]");
    
    /**
     * Returns the control servlet path. The path consists of the web application's mount-point
     * specified in the <code>ofbiz-component.xml</code> file and the servlet mapping specified
     * in the web application's <code>web.xml</code> file.
     * <p>
     * SCIPIO: NOTE: This stock method always returns with a trailing slash (unless null).
     * 
     * 
     * @param webAppInfo
     * @param optional SCIPIO: if true, return null if not found; otherwise throw IllegalArgumentException (added 2017-11-18)
     * @throws IOException
     * @throws SAXException
     */
    public static String getControlServletPath(WebappInfo webAppInfo, boolean optional) throws IOException, SAXException {
        Assert.notNull("webAppInfo", webAppInfo);
        // SCIPIO: Go through cache first. No need to synchronize, doesn't matter.
        String res = controlServletPathWebappInfoCache.get(webAppInfo.getContextRoot()); // key on context root (global unique)
        if (res != null) {
            // We take empty string to mean lookup found nothing
            if (res.isEmpty()) {
                if (optional) return null; // SCIPIO
                else throw new IllegalArgumentException("org.ofbiz.webapp.control.ControlServlet mapping not found in " + webAppInfo.getLocation() + webAppFileName);
            }
            else {
                return res;
            }
        }
        else {
            String servletMapping = null;
            WebXml webXml = getWebXml(webAppInfo);
            ServletDef controlServletDef = ControlServlet.getControlServletDefFromWebXml(webXml); // SCIPIO: 2017-12-05: factored out and fixed logic
            if (controlServletDef != null) {
                servletMapping = ServletUtil.getServletMapping(webXml, controlServletDef.getServletName()); // SCIPIO: 2017-12-05: delegated
            }
            if (servletMapping == null) {
                // SCIPIO: empty string means we did lookup and failed
                controlServletPathWebappInfoCache.put(webAppInfo.getContextRoot(), "");
                if (optional) return null; // SCIPIO
                else throw new IllegalArgumentException("org.ofbiz.webapp.control.ControlServlet mapping not found in " + webAppInfo.getLocation() + webAppFileName);
            }
            servletMapping = servletMapping.replace("*", "");
            if (!servletMapping.endsWith("/")) { // SCIPIO: 2017-12-05: extra guarantee the path ends with trailing slash
                servletMapping += "/"; 
            }
            String servletPath = webAppInfo.contextRoot.concat(servletMapping);
            // SCIPIO: save result
            controlServletPathWebappInfoCache.put(webAppInfo.getContextRoot(), servletPath);
            return servletPath;
        }
    }
    
    /**
     * Returns the control servlet path. The path consists of the web application's mount-point
     * specified in the <code>ofbiz-component.xml</code> file and the servlet mapping specified
     * in the web application's <code>web.xml</code> file.
     * 
     * @param webAppInfo
     * @throws IOException
     * @throws SAXException
     */
    public static String getControlServletPath(WebappInfo webAppInfo) throws IOException, SAXException, IllegalArgumentException {
        return getControlServletPath(webAppInfo, false);
    }
    
    /**
     * SCIPIO: Returns the control servlet path with no exceptions generated and with a terminating slash,
     * or null. The path consists of the web application's mount-point
     * specified in the <code>ofbiz-component.xml</code> file and the servlet mapping specified
     * in the web application's <code>web.xml</code> file.
     * 
     * @param webAppInfo
     * @throws IOException
     * @throws SAXException
     */
    public static String getControlServletPathSafe(WebappInfo webAppInfo) {
        String controlPath = null;
        try {
            controlPath = WebAppUtil.getControlServletPath(webAppInfo);
        } catch (Exception e) {
            ; // Control servlet may not exist; don't treat as error
        }
        return controlPath;
    }
    
    /**
     * SCIPIO: Returns the control servlet path with no exceptions generated and with a terminating slash,
     * or null. The path consists of the web application's mount-point
     * specified in the <code>ofbiz-component.xml</code> file and the servlet mapping specified
     * in the web application's <code>web.xml</code> file.
     * @deprecated 2017-12-05: call {@link #getControlServletPathSafe(WebappInfo)} instead;
     * the central {@link #getControlServletPath} method already ensured a trailing slash,
     * so this one actually becomes misleading. 
     * 
     * @param webAppInfo
     * @throws IOException
     * @throws SAXException
     */
    @Deprecated
    public static String getControlServletPathSafeSlash(WebappInfo webAppInfo) {
        return getControlServletPathSafe(webAppInfo);
    }

    /**
     * SCIPIO: Strips the result of a {@link #getControlServletPath} call to
     * get the control servlet mapping for given webappInfo, WITHOUT the
     * webapp context root. There is never a terminating slash, except if root,
     * where it will be "/".
     * Added 2017-11-30.
     */
    public static String getControlServletOnlyPathFromFull(WebappInfo webAppInfo, String controlPath) {
        if (controlPath != null) {
            if (webAppInfo.contextRoot != null && !webAppInfo.contextRoot.isEmpty() && !"/".equals(webAppInfo.contextRoot)) {
                controlPath = controlPath.substring(webAppInfo.contextRoot.length());
            }
            if (controlPath.length() > 1 && controlPath.endsWith("/")) {
                controlPath = controlPath.substring(0, controlPath.length() - 1);
            }
            if (controlPath.length() == 0) {
                controlPath = "/";
            }
            return controlPath;
        } else {
            return null;
        }
    }
    
    /**
     * SCIPIO: Gets the control servlet mapping for given webappInfo, WITHOUT the
     * webapp context root. There is never a terminating slash, except if root,
     * where it will be "/".
     */
    public static String getControlServletOnlyPath(WebappInfo webAppInfo) throws IOException, SAXException {
        String controlPath = WebAppUtil.getControlServletPath(webAppInfo);
        return getControlServletOnlyPathFromFull(webAppInfo, controlPath);
    }
    
    /**
     * SCIPIO: Gets the control servlet mapping for given webappInfo, WITHOUT the
     * webapp context root, throwing no exceptions. There is never a terminating slash, 
     * except if root, where it will be "/".
     */
    public static String getControlServletOnlyPathSafe(WebappInfo webAppInfo) {
        String controlPath = null;
        try {
            controlPath = WebAppUtil.getControlServletOnlyPath(webAppInfo);
        } catch (Exception e) {
            ; // Control servlet may not exist; don't treat as error
        }
        return controlPath;
    }
    
    /**
     * Returns the <code>WebappInfo</code> instance associated to the specified web site ID.
     * Throws <code>IllegalArgumentException</code> if the web site ID was not found.
     * 
     * @param webSiteId
     * @throws IOException
     * @throws SAXException
     */
    public static WebappInfo getWebappInfoFromWebsiteId(String webSiteId) throws IOException, SAXException {
        Assert.notNull("webSiteId", webSiteId);
        // SCIPIO: Go through cache first. No need to synchronize, doesn't matter.
        WebappInfo res = webappInfoWebSiteIdCache.get(webSiteId);
        if (res != null) {
            return res;
        }
        else {
            for (WebappInfo webAppInfo : ComponentConfig.getAllWebappResourceInfos()) {
                if (webSiteId.equals(WebAppUtil.getWebSiteId(webAppInfo))) {
                    webappInfoWebSiteIdCache.put(webSiteId, webAppInfo); // SCIPIO: save in cache
                    return webAppInfo;
                }
            }
        }
        // SCIPIO: much clearer message
        //throw new IllegalArgumentException("Web site ID '" + webSiteId + "' not found.");
        throw new IllegalArgumentException("Could not get webapp info for website ID '" + webSiteId 
                + "'; the website may not exist, or may not have a webapp (web.xml)"
                + ", or its webapp may be shadowed/overridden in the system (ofbiz_component.xml)");
    }
    
    /**
     * SCIPIO: Returns the <code>WebappInfo</code> instance that has the same mount-point prefix as
     * the given path.
     * <p>
     * <strong>WARN:</strong> Webapp mounted on root (/*) will usually cause a catch-all here.
     * <p>
     * NOTE: This only works for paths starting from webapp contextPath; 
     * if it contains extra prefix such as webappPathPrefix, this will throw exception
     * or return the root webapp (if any mapped to /).
     */
    public static WebappInfo getWebappInfoFromPath(String serverName, String path, boolean stripQuery) throws IOException, SAXException {
        Assert.notNull("path", path);
        // Must be absolute (NOTE: empty path is valid, designates root-mounted "/" webapp)
        if (path.length() > 0 && !path.startsWith("/")) {
            throw new IllegalArgumentException("Scipio: Web app for path '" + path + "' not found (must be absolute path).");
        }
        
        if (stripQuery) {
            Matcher m = urlQueryDelimPat.matcher(path); 
            if (m.find()) {
                path = path.substring(0, m.start());
            }
        }
        Map<String, WebappInfo> webappInfosByContextPath = ComponentConfig.getWebappInfosByContextRoot(serverName);
        if (webappInfosByContextPath == null) {
            throw new IllegalArgumentException("Web app for path '" + path + "' not found by context path because server name '"
                    + serverName + "' is not registered");
        }
        while(true) {
            WebappInfo webappInfo = webappInfosByContextPath.get(path);
            if (webappInfo != null) {
                return webappInfo;
            }
            int i = path.lastIndexOf('/');
            if (i < 0) {
                throw new IllegalArgumentException("Web app for path '" + path + "' not found by context path.");
            }
            path = path.substring(0, i);
        }
    }

    @Deprecated
    public static WebappInfo getWebappInfoFromPath(String path) throws IOException, SAXException {
        return getWebappInfoFromPath(null, path, true);
    }

    /**
     * SCIPIO: Returns the <code>WebappInfo</code> instance that the given exact context path as mount-point
     * <p>
     * <strong>WARN:</strong> Webapp mounted on root (/*) will usually cause a catch-all here.
     */
    public static WebappInfo getWebappInfoFromContextPath(String serverName, String contextPath) throws IOException, SAXException {
        Assert.notNull("contextPath", contextPath);
        WebappInfo webappInfo = ComponentConfig.getWebappInfoByContextRoot(serverName, contextPath);
        if (webappInfo == null) {
            throw new IllegalArgumentException("Web app for context path '" + contextPath + "' not found.");
        }
        return webappInfo;
    }
    
    /**
     * SCIPIO: Returns the <code>WebappInfo</code> instance that the given exact context path as mount-point
     * @deprecated use {@link #getWebappInfoFromContextPath(String, String)} and specify sever name
     * <p>
     * <strong>WARN:</strong> Webapp mounted on root (/*) will usually cause a catch-all here.
     */
    @Deprecated
    public static WebappInfo getWebappInfoFromContextPath(String contextPath) throws IOException, SAXException {
        Assert.notNull("contextPath", contextPath);
        WebappInfo webappInfo = ComponentConfig.getWebappInfoByContextRoot(null, contextPath);
        if (webappInfo == null) {
            throw new IllegalArgumentException("Web app for context path '" + contextPath + "' not found.");
        }
        return webappInfo;
    }
    
    /**
     * SCIPIO: Returns the <code>WebappInfo</code> instance for the current request's webapp.
     */
    public static WebappInfo getWebappInfoFromRequest(HttpServletRequest request) throws IOException, SAXException {
        Assert.notNull("request", request);
        String contextPath = request.getContextPath();
        return getWebappInfoFromContextPath(getServerId(request), contextPath);
    }    

    /**
     * Returns the web site ID - as configured in the web application's <code>web.xml</code> file,
     * or <code>null</code> if no web site ID was found.
     * 
     * @param webAppInfo
     * @throws IOException
     * @throws SAXException
     */
    public static String getWebSiteId(WebappInfo webAppInfo) throws IOException, SAXException {
        Assert.notNull("webAppInfo", webAppInfo);
        WebXml webXml = getWebXml(webAppInfo);
        return webXml.getContextParams().get("webSiteId");
    }

    /**
     * SCIPIO: Returns the web site ID - as configured in the web application's <code>web.xml</code> file,
     * or <code>null</code> if no web site ID was found.
     * Added 2018-08-01.
     * @param webXml
     * @throws IOException
     * @throws SAXException
     */
    public static String getWebSiteId(WebXml webXml) throws IOException, SAXException {
        Assert.notNull("webAppInfo", webXml);
        return webXml.getContextParams().get("webSiteId");
    }

    /**
     * Returns a <code>WebXml</code> instance that models the web application's <code>web.xml</code> file.
     * 
     * @param webAppInfo
     * @throws IOException
     * @throws SAXException
     */
    public static WebXml getWebXml(WebappInfo webAppInfo) throws IOException, SAXException {
        Assert.notNull("webAppInfo", webAppInfo);
        String webXmlFileLocation = webAppInfo.getLocation().concat(webAppFileName);
        // SCIPIO: TEMPORARILY CHANGED THIS TO NON-VALIDATING
        // FIXME: RETURN THIS TO VALIDATING ONCE ALL web.xml VALIDATION ISSUES ARE MERGED FROM UPSTREAM
        // The ofbiz team neglected to do it in this part of code, probably
        // because stock doesn't use it much yet... but we rely on it
        // NOTE: it's also possible this code is missing something that is done in CatalinaContainer
        // but not here... don't know... wait for upstream
        //return parseWebXmlFile(webXmlFileLocation, true);
        return parseWebXmlFile(webXmlFileLocation, false);
    }

    /**
     * Parses the specified <code>web.xml</code> file into a <code>WebXml</code> instance.
     * 
     * @param webXmlFileLocation
     * @param validate
     * @throws IOException
     * @throws SAXException
     */
    public static WebXml parseWebXmlFile(String webXmlFileLocation, boolean validate) throws IOException, SAXException {
        Assert.notEmpty("webXmlFileLocation", webXmlFileLocation);
        WebXml result = webXmlCache.get(webXmlFileLocation);
        if (result == null) {
            File file = new File(webXmlFileLocation);
            if (!file.exists()) {
                throw new IllegalArgumentException(webXmlFileLocation + " does not exist.");
            }
            boolean namespaceAware = true;
            InputStream is = new FileInputStream(file);
            result = new WebXml();
            LocalResolver lr = new LocalResolver(new DefaultHandler());
            ErrorHandler handler = new LocalErrorHandler(webXmlFileLocation, lr);
            Digester digester = DigesterFactory.newDigester(validate, namespaceAware, new WebRuleSet(), false);
            digester.getParser();
            digester.push(result);
            digester.setErrorHandler(handler);
            try {
                digester.parse(new InputSource(is));
            } finally {
                digester.reset();
                if (is != null) {
                    try {
                        is.close();
                    } catch (Throwable t) {
                        Debug.logError(t, "Exception thrown while parsing " + webXmlFileLocation + ": ", module);
                    }
                }
            }
            result = webXmlCache.putIfAbsentAndGet(webXmlFileLocation, result);
        }
        return result;
    }

    /**
     * SCIPIO: Returns the web.xml context-params for webappInfo.
     */
    public static Map<String, String> getWebappContextParams(WebappInfo webappInfo) {
        WebXml webXml;
        try {
            webXml = WebAppUtil.getWebXml(webappInfo);
            Map<String, String> contextParams = webXml.getContextParams();
            return contextParams != null ? contextParams : Collections.<String, String> emptyMap();
        } catch (Exception e) {
            throw new IllegalArgumentException("Web app xml definition for webapp with context root '" + webappInfo.contextRoot + "' not found.", e);
        }
    }
    
    /**
     * SCIPIO: Returns the web.xml context-params for webappInfo, with no exceptions thrown if anything missing.
     */
    public static Map<String, String> getWebappContextParamsSafe(WebappInfo webappInfo) {
        try {
            return getWebappContextParams(webappInfo);
        } catch (Exception e) {
            return Collections.<String, String> emptyMap();
        }
    }
    
    /**
     * SCIPIO: Returns the web.xml context-params for webSiteId.
     */
    public static Map<String, String> getWebappContextParams(String webSiteId) {
        WebappInfo webappInfo;
        WebXml webXml;
        try {
            webappInfo = WebAppUtil.getWebappInfoFromWebsiteId(webSiteId);
            webXml = WebAppUtil.getWebXml(webappInfo);
            Map<String, String> contextParams = webXml.getContextParams();
            return contextParams != null ? contextParams : Collections.<String, String> emptyMap();
        } catch (Exception e) {
            throw new IllegalArgumentException("Web app xml definition for webSiteId '" + webSiteId + "' not found.", e);
        }
    }
    
    /**
     * SCIPIO: Returns the web.xml context-params for webSiteId, with no exceptions thrown if anything missing.
     */
    public static Map<String, String> getWebappContextParamsSafe(String webSiteId) {
        try {
            return getWebappContextParams(webSiteId);
        } catch (Exception e) {
            return Collections.<String, String> emptyMap();
        }
    }

    /**
     * SCIPIO: Obtains the delegator from current request in a read-only (does not create session
     * or populate any attributes), best-effort fashion.
     * <p>
     * WARN: TODO: REVIEW: For tenant delegators, this may be one request late
     * in returning the tenant delegator, during the tenant login; implications unclear.
     * DEV NOTE: If this is fixed in the future, it may need to do redundant tenant
     * delegator preparation.
     * <p>
     * Added 2018-07-31.
     */
    public static Delegator getDelegatorReadOnly(HttpServletRequest request) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        if (delegator != null) {
            return delegator;
        }
        HttpSession session = request.getSession(false); // do not create session
        if (session != null) {
            delegator = (Delegator) session.getAttribute("delegator");
            if (delegator != null) {
                return delegator;
            }
            String delegatorName = (String) session.getAttribute("delegatorName");
            if (delegatorName != null) {
                delegator = DelegatorFactory.getDelegator(delegatorName);
                if (delegator != null) {
                    return delegator;
                } else {
                    Debug.logError("ERROR: delegator factory returned null for delegatorName \"" 
                            + delegatorName + "\" from session attributes", module);
                }
            }
        }
        delegator = (Delegator) request.getServletContext().getAttribute("delegator");
        if (delegator == null) {
            // NOTE: this means the web.xml is not properly configured, because servlet context
            // delegator should have been made available by ContextFilter.init.
            Debug.logError("ERROR: delegator not found in servlet context; please make sure the webapp's"
                    + " web.xml file is properly configured to load ContextFilter and specify entityDelegatorName", module); 
        }
        return delegator;
    }

    /**
     * SCIPIO: Obtains the delegator from current request in a read-only (does not create session
     * or populate any attributes), best-effort fashion, safe for calling from early filters.
     * <p>
     * WARN: TODO: REVIEW: For tenant delegators, this may be one request late
     * in returning the tenant delegator, during the tenant login; implications unclear.
     * DEV NOTE: If this is fixed in the future, it may need to do redundant tenant
     * delegator preparation.
     * <p>
     * Added 2018-07-31.
     */
    public static Delegator getDelegatorFilterSafe(HttpServletRequest request) {
        return getDelegatorReadOnly(request);
    }

    /**
     * SCIPIO: Obtains the dispatcher from current request in a read-only (does not create session
     * or populate any attributes), best-effort fashion.
     * <p>
     * Added 2018-07-31.
     */
    public static LocalDispatcher getDispatcherReadOnly(HttpServletRequest request, Delegator delegator) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        if (dispatcher != null) {
            return dispatcher;
        }
        HttpSession session = request.getSession(false); // do not create session
        if (session != null) {
            dispatcher = (LocalDispatcher) session.getAttribute("dispatcher");
            if (dispatcher != null) {
                return dispatcher;
            }
        }
        dispatcher = (LocalDispatcher) request.getServletContext().getAttribute("dispatcher");
        if (delegator == null) {
            // NOTE: this means the web.xml is not properly configured, because servlet context
            // dispatcher should have been made available by ContextFilter.init.
            Debug.logError("ERROR: dispatcher not found in servlet context; please make sure the webapp's"
                    + " web.xml file is properly configured to load ContextFilter and specify localDispatcherName", module); 
        }
        return dispatcher;
    }

    /**
     * SCIPIO: Obtains the dispatcher from current request in a read-only (does not create session
     * or populate any attributes), best-effort fashion, safe for calling from early filters.
     * <p>
     * Added 2018-07-31.
     */
    public static LocalDispatcher getDispatcherFilterSafe(HttpServletRequest request, Delegator delegator) {
        return getDispatcherReadOnly(request, delegator);
    }

    /**
     * SCIPIO: Gets server ID from request.
     */
    public static String getServerId(HttpServletRequest request) {
        return getServerId(request.getServletContext());
    }

    /**
     * SCIPIO: Gets server ID from servlet context.
     */
    public static String getServerId(ServletContext servletContext) {
        return (String) servletContext.getAttribute("_serverId");
    }
    
    /**
     * SCIPIO: Gets server ID from a render context.
     * <p>
     * FIXME: This currently will always returns null!!
     * Render context (in emails, sitemap) does not set serverId!
     */
    public static String getServerId(Map<String, Object> context) {
        // FIXME: this is a made-up context variable, never set by ofbiz, placeholder...
        return (String) context.get("_serverId");
    }

    private WebAppUtil() {}
}
