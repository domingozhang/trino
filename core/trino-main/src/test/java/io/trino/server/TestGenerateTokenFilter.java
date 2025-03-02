/*
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
package io.trino.server;

import com.google.inject.Binder;
import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import com.google.inject.Module;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.HttpRequestFilter;
import io.airlift.http.client.Request;
import io.airlift.http.client.StringResponseHandler.StringResponse;
import io.airlift.http.client.jetty.JettyHttpClient;
import io.trino.server.security.ResourceSecurity;
import io.trino.server.testing.TestingTrinoServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

import static io.airlift.http.client.HttpClientBinder.httpClientBinder;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static io.airlift.http.client.TraceTokenRequestFilter.TRACETOKEN_HEADER;
import static io.airlift.jaxrs.JaxrsBinder.jaxrsBinder;
import static io.airlift.testing.Assertions.assertInstanceOf;
import static io.airlift.testing.Closeables.closeAll;
import static io.trino.server.security.ResourceSecurity.AccessType.PUBLIC;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;
import static org.testng.Assert.assertEquals;

@TestInstance(PER_CLASS)
@Execution(CONCURRENT)
public class TestGenerateTokenFilter
{
    private JettyHttpClient httpClient;
    private TestingTrinoServer server;
    private GenerateTraceTokenRequestFilter filter;

    @BeforeAll
    public void setup()
    {
        server = TestingTrinoServer.builder()
                .setAdditionalModule(new TestGenerateTokenFilterModule())
                .build();
        httpClient = (JettyHttpClient) server.getInstance(Key.get(HttpClient.class, GenerateTokenFilterTest.class));

        // extract the filter
        List<HttpRequestFilter> filters = httpClient.getRequestFilters();
        assertEquals(filters.size(), 2);
        assertInstanceOf(filters.get(1), GenerateTraceTokenRequestFilter.class);
        filter = (GenerateTraceTokenRequestFilter) filters.get(1);
    }

    @AfterAll
    public void tearDown()
            throws Exception
    {
        closeAll(server, httpClient);
        server = null;
        httpClient = null;
    }

    @Test
    public void testTraceToken()
    {
        Request request = prepareGet().setUri(server.getBaseUrl().resolve("/testing/echo_token")).build();
        StringResponse response = httpClient.execute(request, createStringResponseHandler());
        assertEquals(response.getStatusCode(), SC_OK);
        assertEquals(response.getBody(), filter.getLastToken());
    }

    @Retention(RUNTIME)
    @Target(ElementType.PARAMETER)
    @BindingAnnotation
    private @interface GenerateTokenFilterTest {}

    @Path("/testing")
    public static class TestResource
    {
        @ResourceSecurity(PUBLIC)
        @GET
        @Path("/echo_token")
        public String echoToken(@HeaderParam(TRACETOKEN_HEADER) String token)
        {
            return token;
        }
    }

    static class TestGenerateTokenFilterModule
            implements Module
    {
        @Override
        public void configure(Binder binder)
        {
            jaxrsBinder(binder).bind(TestResource.class);
            httpClientBinder(binder)
                    .bindHttpClient("test", GenerateTokenFilterTest.class)
                    .withTracing()
                    .withFilter(GenerateTraceTokenRequestFilter.class);
        }
    }
}
