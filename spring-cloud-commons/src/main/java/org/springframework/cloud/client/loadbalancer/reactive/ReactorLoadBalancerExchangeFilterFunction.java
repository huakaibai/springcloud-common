/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.cloud.client.loadbalancer.reactive;

import java.net.URI;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.ClientRequestContext;
import org.springframework.cloud.client.loadbalancer.CompletionContext;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycle;
import org.springframework.cloud.client.loadbalancer.LoadBalancerLifecycleValidator;
import org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;

/**
 * An {@link ExchangeFilterFunction} that uses {@link ReactiveLoadBalancer} to execute
 * requests against a correct {@link ServiceInstance}.
 *
 * @author Olga Maciaszek-Sharma
 * @since 2.2.0
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class ReactorLoadBalancerExchangeFilterFunction implements ExchangeFilterFunction {

	private static final Log LOG = LogFactory.getLog(ReactorLoadBalancerExchangeFilterFunction.class);

	private final ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;

	private final LoadBalancerProperties properties;

	public ReactorLoadBalancerExchangeFilterFunction(ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory,
			LoadBalancerProperties properties) {
		this.loadBalancerFactory = loadBalancerFactory;
		this.properties = properties;
	}

	@Override
	public Mono<ClientResponse> filter(ClientRequest clientRequest, ExchangeFunction next) {
		URI originalUrl = clientRequest.url();
		String serviceId = originalUrl.getHost();
		if (serviceId == null) {
			String message = String.format("Request URI does not contain a valid hostname: %s", originalUrl.toString());
			if (LOG.isWarnEnabled()) {
				LOG.warn(message);
			}
			return Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST).body(message).build());
		}
		Set<LoadBalancerLifecycle> supportedLifecycleProcessors = LoadBalancerLifecycleValidator
				.getSupportedLifecycleProcessors(
						loadBalancerFactory.getInstances(serviceId, LoadBalancerLifecycle.class),
						ClientRequestContext.class, ClientResponse.class, ServiceInstance.class);
		String hint = getHint(serviceId);
		DefaultRequest<ClientRequestContext> lbRequest = new DefaultRequest<>(
				new ClientRequestContext(clientRequest, hint));
		supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));
		return choose(serviceId, lbRequest).flatMap(lbResponse -> {
			ServiceInstance instance = lbResponse.getServer();
			if (instance == null) {
				String message = serviceInstanceUnavailableMessage(serviceId);
				if (LOG.isWarnEnabled()) {
					LOG.warn(message);
				}
				supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
						.onComplete(new CompletionContext<>(CompletionContext.Status.DISCARD, lbResponse)));
				return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
						.body(serviceInstanceUnavailableMessage(serviceId)).build());
			}

			if (LOG.isDebugEnabled()) {
				LOG.debug(String.format("Load balancer has retrieved the instance for service %s: %s", serviceId,
						instance.getUri()));
			}
			ClientRequest newRequest = buildClientRequest(clientRequest, reconstructURI(instance, originalUrl));
			return next.exchange(newRequest)
					.doOnError(throwable -> supportedLifecycleProcessors.forEach(
							lifecycle -> lifecycle.onComplete(new CompletionContext<ClientResponse, ServiceInstance>(
									CompletionContext.Status.FAILED, throwable, lbResponse))))
					.doOnSuccess(clientResponse -> supportedLifecycleProcessors.forEach(
							lifecycle -> lifecycle.onComplete(new CompletionContext<ClientResponse, ServiceInstance>(
									CompletionContext.Status.SUCCESS, lbResponse, clientResponse))));
		});
	}

	protected URI reconstructURI(ServiceInstance instance, URI original) {
		return LoadBalancerUriTools.reconstructURI(instance, original);
	}

	protected Mono<Response<ServiceInstance>> choose(String serviceId, Request<ClientRequestContext> request) {
		ReactiveLoadBalancer<ServiceInstance> loadBalancer = loadBalancerFactory.getInstance(serviceId);
		if (loadBalancer == null) {
			return Mono.just(new EmptyResponse());
		}
		return Mono.from(loadBalancer.choose(request));
	}

	private String serviceInstanceUnavailableMessage(String serviceId) {
		return "Load balancer does not contain an instance for the service " + serviceId;
	}

	private ClientRequest buildClientRequest(ClientRequest request, URI uri) {
		return ClientRequest.create(request.method(), uri).headers(headers -> headers.addAll(request.headers()))
				.cookies(cookies -> cookies.addAll(request.cookies()))
				.attributes(attributes -> attributes.putAll(request.attributes())).body(request.body()).build();
	}

	private String getHint(String serviceId) {
		String defaultHint = properties.getHint().getOrDefault("default", "default");
		String hintPropertyValue = properties.getHint().get(serviceId);
		return hintPropertyValue != null ? hintPropertyValue : defaultHint;
	}

}
