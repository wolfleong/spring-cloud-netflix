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

package org.springframework.cloud.netflix.zuul;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.netflix.zuul.FilterLoader;
import com.netflix.zuul.ZuulFilter;
import com.netflix.zuul.filters.FilterRegistry;
import com.netflix.zuul.filters.ZuulServletFilter;
import com.netflix.zuul.http.ZuulServlet;
import com.netflix.zuul.monitoring.CounterFactory;
import com.netflix.zuul.monitoring.TracerFactory;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.cloud.client.actuator.HasFeatures;
import org.springframework.cloud.client.discovery.event.HeartbeatEvent;
import org.springframework.cloud.client.discovery.event.HeartbeatMonitor;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.discovery.event.ParentHeartbeatEvent;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;
import org.springframework.cloud.netflix.zuul.filters.CompositeRouteLocator;
import org.springframework.cloud.netflix.zuul.filters.RouteLocator;
import org.springframework.cloud.netflix.zuul.filters.SimpleRouteLocator;
import org.springframework.cloud.netflix.zuul.filters.ZuulProperties;
import org.springframework.cloud.netflix.zuul.filters.post.SendErrorFilter;
import org.springframework.cloud.netflix.zuul.filters.post.SendResponseFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.DebugFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.FormBodyWrapperFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.Servlet30WrapperFilter;
import org.springframework.cloud.netflix.zuul.filters.pre.ServletDetectionFilter;
import org.springframework.cloud.netflix.zuul.filters.route.SendForwardFilter;
import org.springframework.cloud.netflix.zuul.metrics.DefaultCounterFactory;
import org.springframework.cloud.netflix.zuul.metrics.EmptyCounterFactory;
import org.springframework.cloud.netflix.zuul.metrics.EmptyTracerFactory;
import org.springframework.cloud.netflix.zuul.web.ZuulController;
import org.springframework.cloud.netflix.zuul.web.ZuulHandlerMapping;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static java.util.Collections.emptyList;

/**
 * Zuul 服务自动配置类
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Biju Kunjummen
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ ZuulProperties.class })
@ConditionalOnClass({ ZuulServlet.class, ZuulServletFilter.class })
@ConditionalOnBean(ZuulServerMarkerConfiguration.Marker.class)
// Make sure to get the ServerProperties from the same place as a normal web app would
// FIXME @Import(ServerPropertiesAutoConfiguration.class)
public class ZuulServerAutoConfiguration {

	/**
	 * Zuul 配置
	 */
	@Autowired
	protected ZuulProperties zuulProperties;

	/**
	 * WEB 服务配置
	 */
	@Autowired
	protected ServerProperties server;

	/**
	 * 错误处理器
	 */
	@Autowired(required = false)
	private ErrorController errorController;

	/**
	 * 跨域配置
	 */
	private Map<String, CorsConfiguration> corsConfigurations;

	/**
	 * 所有 Web MVC 配置器
	 */
	@Autowired(required = false)
	private List<WebMvcConfigurer> configurers = emptyList();

	@Bean
	public HasFeatures zuulFeature() {
		return HasFeatures.namedFeature("Zuul (Simple)",
				ZuulServerAutoConfiguration.class);
	}

	/**
	 * 获取容器中所有的路由定位器, 组装成复合的路由定位器, 作为主要路由定位器
	 */
	@Bean
	@Primary
	public CompositeRouteLocator primaryRouteLocator(
			Collection<RouteLocator> routeLocators) {
		return new CompositeRouteLocator(routeLocators);
	}

	/**
	 * 通过路由配置创建 SimpleRouteLocator
	 */
	@Bean
	@ConditionalOnMissingBean(SimpleRouteLocator.class)
	public SimpleRouteLocator simpleRouteLocator() {
		return new SimpleRouteLocator(this.server.getServlet().getContextPath(),
				this.zuulProperties);
	}

	/**
	 * 创建 ZuulController 对象
	 */
	@Bean
	public ZuulController zuulController() {
		return new ZuulController();
	}

	/**
	 * 创建 ZuulHandlerMapping 对象
	 */
	@Bean
	public ZuulHandlerMapping zuulHandlerMapping(RouteLocator routes,
			ZuulController zuulController) {
		//创建 ZuulHandlerMapping
		ZuulHandlerMapping mapping = new ZuulHandlerMapping(routes, zuulController);
		//设置 ErrorController
		mapping.setErrorController(this.errorController);
		//设置跨域配置
		mapping.setCorsConfigurations(getCorsConfigurations());
		return mapping;
	}

	/**
	 * 获取跨域配置
	 */
	protected final Map<String, CorsConfiguration> getCorsConfigurations() {
		//如果跨域配置为空
		if (this.corsConfigurations == null) {
			//创建 ZuulCorsRegistry
			ZuulCorsRegistry registry = new ZuulCorsRegistry();
			//调用配置器, 注册跨域配置到 ZuulCorsRegistry 中
			this.configurers.forEach(configurer -> configurer.addCorsMappings(registry));
			//设置到 corsConfigurations
			this.corsConfigurations = registry.getCorsConfigurations();
		}
		//返回
		return this.corsConfigurations;
	}

	@Bean
	public ApplicationListener<ApplicationEvent> zuulRefreshRoutesListener() {
		//创建刷新路由监听器
		return new ZuulRefreshListener();
	}

	/**
	 * 创建 ZuulServlet
	 */
	@Bean
	@ConditionalOnMissingBean(name = "zuulServlet")
	@ConditionalOnProperty(name = "zuul.use-filter", havingValue = "false",
			matchIfMissing = true)
	public ServletRegistrationBean zuulServlet() {
		ServletRegistrationBean<ZuulServlet> servlet = new ServletRegistrationBean<>(
				new ZuulServlet(), this.zuulProperties.getServletPattern());
		// The whole point of exposing this servlet is to provide a route that doesn't
		// buffer requests.
		servlet.addInitParameter("buffer-requests", "false");
		return servlet;
	}

	/**
	 * 创建 ZuulServletFilter
	 */
	@Bean
	@ConditionalOnMissingBean(name = "zuulServletFilter")
	@ConditionalOnProperty(name = "zuul.use-filter", havingValue = "true",
			matchIfMissing = false)
	public FilterRegistrationBean zuulServletFilter() {
		final FilterRegistrationBean<ZuulServletFilter> filterRegistration = new FilterRegistrationBean<>();
		filterRegistration.setUrlPatterns(
				Collections.singleton(this.zuulProperties.getServletPattern()));
		filterRegistration.setFilter(new ZuulServletFilter());
		filterRegistration.setOrder(Ordered.LOWEST_PRECEDENCE);
		// The whole point of exposing this servlet is to provide a route that doesn't
		// buffer requests.
		filterRegistration.addInitParameter("buffer-requests", "false");
		return filterRegistration;
	}

	//相关前置过滤器
	// pre filters

	@Bean
	public ServletDetectionFilter servletDetectionFilter() {
		return new ServletDetectionFilter();
	}

	@Bean
	@ConditionalOnMissingBean
	public FormBodyWrapperFilter formBodyWrapperFilter() {
		return new FormBodyWrapperFilter();
	}

	@Bean
	@ConditionalOnMissingBean
	public DebugFilter debugFilter() {
		return new DebugFilter();
	}

	@Bean
	@ConditionalOnMissingBean
	public Servlet30WrapperFilter servlet30WrapperFilter() {
		return new Servlet30WrapperFilter();
	}

	//相关后置过滤器
	// post filters

	@Bean
	public SendResponseFilter sendResponseFilter(ZuulProperties properties) {
		return new SendResponseFilter(zuulProperties);
	}

	@Bean
	public SendErrorFilter sendErrorFilter() {
		return new SendErrorFilter();
	}

	@Bean
	public SendForwardFilter sendForwardFilter() {
		return new SendForwardFilter();
	}

	@Bean
	@ConditionalOnProperty("zuul.ribbon.eager-load.enabled")
	public ZuulRouteApplicationContextInitializer zuulRoutesApplicationContextInitiazer(
			SpringClientFactory springClientFactory) {
		return new ZuulRouteApplicationContextInitializer(springClientFactory,
				zuulProperties);
	}

	@Configuration(proxyBeanMethods = false)
	protected static class ZuulFilterConfiguration {

		@Autowired
		private Map<String, ZuulFilter> filters;

		@Bean
		public ZuulFilterInitializer zuulFilterInitializer(CounterFactory counterFactory,
				TracerFactory tracerFactory) {
			FilterLoader filterLoader = FilterLoader.getInstance();
			FilterRegistry filterRegistry = FilterRegistry.instance();
			return new ZuulFilterInitializer(this.filters, counterFactory, tracerFactory,
					filterLoader, filterRegistry);
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(MeterRegistry.class)
	protected static class ZuulCounterFactoryConfiguration {

		@Bean
		@ConditionalOnBean(MeterRegistry.class)
		@ConditionalOnMissingBean(CounterFactory.class)
		public CounterFactory counterFactory(MeterRegistry meterRegistry) {
			return new DefaultCounterFactory(meterRegistry);
		}

	}

	@Configuration(proxyBeanMethods = false)
	protected static class ZuulMetricsConfiguration {

		@Bean
		@ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
		@ConditionalOnMissingBean(CounterFactory.class)
		public CounterFactory counterFactory() {
			return new EmptyCounterFactory();
		}

		@ConditionalOnMissingBean(TracerFactory.class)
		@Bean
		public TracerFactory tracerFactory() {
			return new EmptyTracerFactory();
		}

	}

	/**
	 * Zuul配置刷新监听器
	 */
	private static class ZuulRefreshListener
			implements ApplicationListener<ApplicationEvent> {

		@Autowired
		private ZuulHandlerMapping zuulHandlerMapping;

		private HeartbeatMonitor heartbeatMonitor = new HeartbeatMonitor();

		@Override
		public void onApplicationEvent(ApplicationEvent event) {
			if (event instanceof ContextRefreshedEvent
					|| event instanceof RefreshScopeRefreshedEvent
					|| event instanceof RoutesRefreshedEvent
					|| event instanceof InstanceRegisteredEvent) {
				reset();
			}
			else if (event instanceof ParentHeartbeatEvent) {
				ParentHeartbeatEvent e = (ParentHeartbeatEvent) event;
				resetIfNeeded(e.getValue());
			}
			else if (event instanceof HeartbeatEvent) {
				HeartbeatEvent e = (HeartbeatEvent) event;
				resetIfNeeded(e.getValue());
			}
		}

		private void resetIfNeeded(Object value) {
			if (this.heartbeatMonitor.update(value)) {
				reset();
			}
		}

		private void reset() {
			this.zuulHandlerMapping.setDirty(true);
		}

	}

	/**
	 * Zuul 跨域注册表
	 */
	private static class ZuulCorsRegistry extends CorsRegistry {

		@Override
		protected Map<String, CorsConfiguration> getCorsConfigurations() {
			return super.getCorsConfigurations();
		}

	}

}
