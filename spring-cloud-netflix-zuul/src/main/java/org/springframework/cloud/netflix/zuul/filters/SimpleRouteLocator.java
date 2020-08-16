/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.zuul.filters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.netflix.zuul.filters.ZuulProperties.ZuulRoute;
import org.springframework.cloud.netflix.zuul.util.RequestUtils;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;

/**
 * SimpleRouteLocator 接口的简单实现
 * Simple {@link RouteLocator} based on configuration data held in {@link ZuulProperties}.
 *
 * @author Dave Syer
 */
public class SimpleRouteLocator implements RouteLocator, Ordered {

	private static final Log log = LogFactory.getLog(SimpleRouteLocator.class);

	private static final int DEFAULT_ORDER = 0;

	/**
	 * 路由配置
	 */
	private ZuulProperties properties;

	/**
	 * 路径匹配器
	 */
	private PathMatcher pathMatcher = new AntPathMatcher();

	private String dispatcherServletPath = "/";

	/**
	 * zuul 的 ServletPath 路径
	 */
	private String zuulServletPath;

	/**
	 * 路由缓存
	 */
	private AtomicReference<Map<String, ZuulRoute>> routes = new AtomicReference<>();

	private int order = DEFAULT_ORDER;

	/**
	 * @param servletPath Servlet 配置的 path
	 * @param properties 路由配置
	 */
	public SimpleRouteLocator(String servletPath, ZuulProperties properties) {
		this.properties = properties;
		if (StringUtils.hasText(servletPath)) {
			this.dispatcherServletPath = servletPath;
		}

		this.zuulServletPath = properties.getServletPath();
	}

	@Override
	public List<Route> getRoutes() {
		List<Route> values = new ArrayList<>();
		//遍历缓存中的路由
		for (Entry<String, ZuulRoute> entry : getRoutesMap().entrySet()) {
			ZuulRoute route = entry.getValue();
			String path = route.getPath();
			try {
				//通过 ZuulRoute 创建 Route 对象, 并添加到列表中
				values.add(getRoute(route, path));
			}
			catch (Exception e) {
				if (log.isWarnEnabled()) {
					log.warn("Invalid route, routeId: " + route.getId()
							+ ", routeServiceId: " + route.getServiceId() + ", msg: "
							+ e.getMessage());
				}
				if (log.isDebugEnabled()) {
					log.debug("", e);
				}
			}
		}
		//返回
		return values;
	}

	@Override
	public Collection<String> getIgnoredPaths() {
		return this.properties.getIgnoredPatterns();
	}

	@Override
	public Route getMatchingRoute(final String path) {

		return getSimpleMatchingRoute(path);

	}

	protected Map<String, ZuulRoute> getRoutesMap() {
		if (this.routes.get() == null) {
			//定位路由, 并且添加到缓存中
			this.routes.set(locateRoutes());
		}
		return this.routes.get();
	}

	protected Route getSimpleMatchingRoute(final String path) {
		if (log.isDebugEnabled()) {
			log.debug("Finding route for path: " + path);
		}

		//加载路由
		// This is called for the initialization done in getRoutesMap()
		getRoutesMap();

		if (log.isDebugEnabled()) {
			log.debug("servletPath=" + this.dispatcherServletPath);
			log.debug("zuulServletPath=" + this.zuulServletPath);
			log.debug("RequestUtils.isDispatcherServletRequest()="
					+ RequestUtils.isDispatcherServletRequest());
			log.debug("RequestUtils.isZuulServletRequest()="
					+ RequestUtils.isZuulServletRequest());
		}

		//处理请求路径
		String adjustedPath = adjustPath(path);

		//获取路由 ZuulRoute 对象
		ZuulRoute route = getZuulRoute(adjustedPath);

		//构造 Route
		return getRoute(route, adjustedPath);
	}

	protected ZuulRoute getZuulRoute(String adjustedPath) {
		//如果不是要忽略的
		if (!matchesIgnoredPatterns(adjustedPath)) {
			//遍历
			for (Entry<String, ZuulRoute> entry : getRoutesMap().entrySet()) {
				String pattern = entry.getKey();
				log.debug("Matching pattern:" + pattern);
				//如果路径匹配, 则返回
				if (this.pathMatcher.match(pattern, adjustedPath)) {
					return entry.getValue();
				}
			}
		}
		return null;
	}

	protected Route getRoute(ZuulRoute route, String path) {
		if (route == null) {
			return null;
		}
		if (log.isDebugEnabled()) {
			log.debug("route matched=" + route);
		}
		//处理是否截取前缀
		String targetPath = path;
		String prefix = this.properties.getPrefix();
		if (prefix.endsWith("/")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		if (path.startsWith(prefix + "/") && this.properties.isStripPrefix()) {
			targetPath = path.substring(prefix.length());
		}
		if (route.isStripPrefix()) {
			int index = route.getPath().indexOf("*") - 1;
			if (index > 0) {
				String routePrefix = route.getPath().substring(0, index);
				targetPath = targetPath.replaceFirst(routePrefix, "");
				prefix = prefix + routePrefix;
			}
		}
		Boolean retryable = this.properties.getRetryable();
		if (route.getRetryable() != null) {
			retryable = route.getRetryable();
		}
		//创建 Route 对象
		return new Route(route.getId(), targetPath, route.getLocation(), prefix,
				retryable,
				route.isCustomSensitiveHeaders() ? route.getSensitiveHeaders() : null,
				route.isStripPrefix());
	}

	/**
	 * 定位路由并添加到缓存
	 * Calculate all the routes and set up a cache for the values. Subclasses can call
	 * this method if they need to implement {@link RefreshableRouteLocator}.
	 */
	protected void doRefresh() {
		this.routes.set(locateRoutes());
	}

	/**
	 * 定位路由
	 * Compute a map of path pattern to route. The default is just a static map from the
	 * {@link ZuulProperties}, but subclasses can add dynamic calculations.
	 * @return map of Zuul routes
	 */
	protected Map<String, ZuulRoute> locateRoutes() {
		//创建 map
		LinkedHashMap<String, ZuulRoute> routesMap = new LinkedHashMap<>();
		//遍历配置的路由, 添加到 map 中
		for (ZuulRoute route : this.properties.getRoutes().values()) {
			routesMap.put(route.getPath(), route);
		}
		//返回
		return routesMap;
	}

	/**
	 * 判断是否为忽略的路径
	 */
	protected boolean matchesIgnoredPatterns(String path) {
		//遍历配置的忽略路径
		for (String pattern : this.properties.getIgnoredPatterns()) {
			log.debug("Matching ignored pattern:" + pattern);
			//如果匹配则返回 true
			if (this.pathMatcher.match(pattern, path)) {
				log.debug("Path " + path + " matches ignored pattern " + pattern);
				return true;
			}
		}
		return false;
	}

	/**
	 * 截掉可能的 ServletContextPath 或 ZuulServletPath
	 */
	private String adjustPath(final String path) {
		String adjustedPath = path;

		//如果是普通的 Servlet 请求且有配置 dispatcherServletPath
		if (RequestUtils.isDispatcherServletRequest()
				&& StringUtils.hasText(this.dispatcherServletPath)) {
			if (!this.dispatcherServletPath.equals("/")
					&& path.startsWith(this.dispatcherServletPath)) {
				//截掉 dispatcherServletPath
				adjustedPath = path.substring(this.dispatcherServletPath.length());
				log.debug("Stripped dispatcherServletPath");
			}
		}
		//如果是 Zuul 的请求且有配置 zuulServletPath
		else if (RequestUtils.isZuulServletRequest()) {
			if (StringUtils.hasText(this.zuulServletPath)
					&& !this.zuulServletPath.equals("/")) {
				//截掉 zuulServletPath
				adjustedPath = path.substring(this.zuulServletPath.length());
				log.debug("Stripped zuulServletPath");
			}
		}
		else {
			// do nothing
		}

		log.debug("adjustedPath=" + adjustedPath);
		return adjustedPath;
	}

	/**
	 * 顺序
	 */
	@Override
	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

}
