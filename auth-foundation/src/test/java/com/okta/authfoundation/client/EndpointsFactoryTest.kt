/*
 * Copyright 2022-Present Okta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.okta.authfoundation.client

import com.google.common.truth.Truth.assertThat
import com.okta.testhelpers.OktaRule
import com.okta.testhelpers.RequestMatchers.method
import com.okta.testhelpers.RequestMatchers.path
import com.okta.testhelpers.testBodyFromFile
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.Test

internal class EndpointsFactoryTest {
    private val mockPrefix = "client_test_responses"

    @get:Rule val oktaRule = OktaRule()

    @Test fun testCachedEndpointsDoesNotMakeNetworkCall(): Unit = runBlocking {
        oktaRule.enqueue(
            method("GET"),
            path("/.well-known/openid-configuration"),
        ) { response ->
            response.testBodyFromFile("$mockPrefix/endpoints.json")
        }
        val cache = InMemoryCache()
        EndpointsFactory.get(
            oktaRule.createConfiguration(cache = cache),
            oktaRule.baseUrl.newBuilder().encodedPath("/.well-known/openid-configuration").build()
        ).assertValid()
        // Second call uses cache.
        EndpointsFactory.get(
            oktaRule.createConfiguration(cache = cache),
            oktaRule.baseUrl.newBuilder().encodedPath("/.well-known/openid-configuration").build()
        ).assertValid()
    }

    @Test fun testNullCacheMakesNetworkCall(): Unit = runBlocking {
        oktaRule.enqueue(
            method("GET"),
            path("/.well-known/openid-configuration"),
        ) { response ->
            response.testBodyFromFile("$mockPrefix/endpoints.json")
        }
        val cache = InMemoryCache()
        val url = oktaRule.baseUrl.newBuilder().encodedPath("/.well-known/openid-configuration").build()
        val cacheKey = EndpointsFactory.prefix + url.toString()
        assertThat(cache.get(cacheKey)).isNull()
        EndpointsFactory.get(
            oktaRule.createConfiguration(cache = cache),
            oktaRule.baseUrl.newBuilder().encodedPath("/.well-known/openid-configuration").build()
        ).assertValid()
        assertThat(cache.get(cacheKey)).startsWith("{") // It's json!
    }

    @Test fun testInvalidCachedEndpointsMakesNetworkCall(): Unit = runBlocking {
        oktaRule.enqueue(
            method("GET"),
            path("/.well-known/openid-configuration"),
        ) { response ->
            response.testBodyFromFile("$mockPrefix/endpoints.json")
        }
        val cache = InMemoryCache()
        val url = oktaRule.baseUrl.newBuilder().encodedPath("/.well-known/openid-configuration").build()
        cache.set(EndpointsFactory.prefix + url.toString(), "invalid")
        EndpointsFactory.get(
            oktaRule.createConfiguration(cache = cache),
            url
        ).assertValid()
    }

    @Test fun testInvalidResponseCausesError(): Unit = runBlocking {
        oktaRule.enqueue(
            method("GET"),
            path("/.well-known/openid-configuration"),
        ) { response ->
            response.setBody("""{"invalid"}""")
        }
        val result = EndpointsFactory.get(
            oktaRule.createConfiguration(cache = InMemoryCache()),
            oktaRule.baseUrl.newBuilder().encodedPath("/.well-known/openid-configuration").build()
        ) as OidcClientResult.Error<OidcEndpoints>
        assertThat(result.exception).hasMessageThat().startsWith("Unexpected JSON token at offset 9")
    }

    @Test fun testNetworkFailureFollowedByNetworkCallResultsInValidEndpoints(): Unit = runBlocking {
        oktaRule.enqueue(
            method("GET"),
            path("/.well-known/openid-configuration"),
        ) { response ->
            response.setResponseCode(500)
        }
        val cache = InMemoryCache()
        val url = oktaRule.baseUrl.newBuilder().encodedPath("/.well-known/openid-configuration").build()
        val result = EndpointsFactory.get(
            oktaRule.createConfiguration(cache = cache),
            url
        ) as OidcClientResult.Error<OidcEndpoints>
        assertThat(result.exception).hasMessageThat().startsWith("HTTP Error: status code - 500")

        oktaRule.enqueue(
            method("GET"),
            path("/.well-known/openid-configuration"),
        ) { response ->
            response.testBodyFromFile("$mockPrefix/endpoints.json")
        }
        EndpointsFactory.get(
            oktaRule.createConfiguration(cache = cache),
            url
        ).assertValid()
    }
}

private fun OidcClientResult<OidcEndpoints>.assertValid() {
    val endpoints = (this as OidcClientResult.Success<OidcEndpoints>).result
    assertThat(endpoints.issuer).isEqualTo("https://example.okta.com/oauth2/default".toHttpUrl())
    assertThat(endpoints.authorizationEndpoint).isEqualTo("https://example.okta.com/oauth2/default/v1/authorize".toHttpUrl())
    assertThat(endpoints.tokenEndpoint).isEqualTo("https://example.okta.com/oauth2/default/v1/token".toHttpUrl())
    assertThat(endpoints.userInfoEndpoint).isEqualTo("https://example.okta.com/oauth2/default/v1/userinfo".toHttpUrl())
    assertThat(endpoints.jwksUri).isEqualTo("https://example.okta.com/oauth2/default/v1/keys".toHttpUrl())
    assertThat(endpoints.introspectionEndpoint).isEqualTo("https://example.okta.com/oauth2/default/v1/introspect".toHttpUrl())
    assertThat(endpoints.revocationEndpoint).isEqualTo("https://example.okta.com/oauth2/default/v1/revoke".toHttpUrl())
    assertThat(endpoints.endSessionEndpoint).isEqualTo("https://example.okta.com/oauth2/default/v1/logout".toHttpUrl())
}
