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
import java.util.List;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;

/**
 * 多个路由定位器的复合实现
 * RouteLocator that composes multiple RouteLocators.
 *
 * @author Johannes Edmeier
 *
 */
public class CompositeRouteLocator implements RefreshableRouteLocator {

	/**
	 * 排好序的路由定位器集合
	 */
	private final Collection<? extends RouteLocator> routeLocators;

	/**
	 * 不明白, 为什么要 rl 这个集合 ?
	 */
	private ArrayList<RouteLocator> rl;

	public CompositeRouteLocator(Collection<? extends RouteLocator> routeLocators) {
		Assert.notNull(routeLocators, "'routeLocators' must not be null");
		rl = new ArrayList<>(routeLocators);
		//排序
		AnnotationAwareOrderComparator.sort(rl);
		this.routeLocators = rl;
	}

	/**
	 * 获取所有的忽略路径匹配
	 */
	@Override
	public Collection<String> getIgnoredPaths() {
		List<String> ignoredPaths = new ArrayList<>();
		for (RouteLocator locator : routeLocators) {
			ignoredPaths.addAll(locator.getIgnoredPaths());
		}
		return ignoredPaths;
	}

	/**
	 * 获取所有的路由
	 */
	@Override
	public List<Route> getRoutes() {
		List<Route> route = new ArrayList<>();
		for (RouteLocator locator : routeLocators) {
			route.addAll(locator.getRoutes());
		}
		return route;
	}

	/**
	 * 获取匹配路径的路由对象
	 */
	@Override
	public Route getMatchingRoute(String path) {
		for (RouteLocator locator : routeLocators) {
			Route route = locator.getMatchingRoute(path);
			if (route != null) {
				return route;
			}
		}
		return null;
	}

	/**
	 * 刷新路由
	 */
	@Override
	public void refresh() {
		//遍历所有路由定位器
		for (RouteLocator locator : routeLocators) {
			//执行刷新
			if (locator instanceof RefreshableRouteLocator) {
				((RefreshableRouteLocator) locator).refresh();
			}
		}
	}

}
