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

package org.springframework.cloud.netflix.zuul.web;

import java.util.Collection;

import javax.servlet.http.HttpServletRequest;

import com.netflix.zuul.context.RequestContext;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.cloud.netflix.zuul.filters.RefreshableRouteLocator;
import org.springframework.cloud.netflix.zuul.filters.Route;
import org.springframework.cloud.netflix.zuul.filters.RouteLocator;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;

/**
 * 处理 Zuul 请求的 HandlerMapping, 主要是维护 Zuul Path 和 ZuulController 的映射关系
 * MVC HandlerMapping that maps incoming request paths to remote services.
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @author João Salavessa
 * @author Biju Kunjummen
 */
public class ZuulHandlerMapping extends AbstractUrlHandlerMapping {

	/**
	 * 路由定位器
	 */
	private final RouteLocator routeLocator;

	/**
	 * 路由请求处理器
	 */
	private final ZuulController zuul;

	/**
	 * 错误处理器
	 */
	private ErrorController errorController;

	private PathMatcher pathMatcher = new AntPathMatcher();

	/**
	 * 是否需要重新注册处理器
	 */
	private volatile boolean dirty = true;

	public ZuulHandlerMapping(RouteLocator routeLocator, ZuulController zuul) {
		this.routeLocator = routeLocator;
		this.zuul = zuul;
		//设置顺序
		setOrder(-200);
	}

	@Override
	protected HandlerExecutionChain getCorsHandlerExecutionChain(
			HttpServletRequest request, HandlerExecutionChain chain,
			CorsConfiguration config) {
		if (config == null) {
			// Allow CORS requests to go to the backend
			return chain;
		}
		return super.getCorsHandlerExecutionChain(request, chain, config);
	}

	public void setErrorController(ErrorController errorController) {
		this.errorController = errorController;
	}

	/**
	 *  setDirty 有两个操作,
	 *  一是重新加载 Route 对象,
	 *  二是设置 zuulHandlerMapping 数据的状态, 以便重新将所有新的 path 与 ZuulController 注册的 Mapping 上
	 */
	public void setDirty(boolean dirty) {
		//标记脏数据
		this.dirty = dirty;
		//刷新路由
		if (this.routeLocator instanceof RefreshableRouteLocator) {
			((RefreshableRouteLocator) this.routeLocator).refresh();
		}
	}

	@Override
	protected Object lookupHandler(String urlPath, HttpServletRequest request)
			throws Exception {
		//如果是错误的处理器的路径, 则不处理
		if (this.errorController != null
				&& urlPath.equals(this.errorController.getErrorPath())) {
			return null;
		}
		//处理忽略的请求路径
		if (isIgnoredPath(urlPath, this.routeLocator.getIgnoredPaths())) {
			return null;
		}
		//如果是转发, 也不处理
		RequestContext ctx = RequestContext.getCurrentContext();
		if (ctx.containsKey("forward.to")) {
			return null;
		}
		//如果是脏数据
		if (this.dirty) {
			synchronized (this) {
				if (this.dirty) {
					//重新注册 Handlers
					registerHandlers();
					this.dirty = false;
				}
			}
		}
		//根据 urlPath 查询处理器
		return super.lookupHandler(urlPath, request);
	}

	private boolean isIgnoredPath(String urlPath, Collection<String> ignored) {
		if (ignored != null) {
			for (String ignoredPath : ignored) {
				if (this.pathMatcher.match(ignoredPath, urlPath)) {
					return true;
				}
			}
		}
		return false;
	}

	private void registerHandlers() {
		Collection<Route> routes = this.routeLocator.getRoutes();
		if (routes.isEmpty()) {
			this.logger.warn("No routes found from RouteLocator");
		}
		else {
			// 遍历路由信息，将路由的路径 和 ZuulController 注册到父类 handlerMap 里
			for (Route route : routes) {
				registerHandler(route.getFullPath(), this.zuul);
			}
		}
	}

}
