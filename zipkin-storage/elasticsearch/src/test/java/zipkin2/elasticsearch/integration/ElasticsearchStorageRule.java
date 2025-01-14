/*
 * Copyright 2015-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.elasticsearch.integration;

import com.google.common.io.Closer;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.logging.LoggingClientBuilder;
import com.linecorp.armeria.common.logging.LogLevel;
import java.util.Arrays;
import java.util.function.Consumer;
import org.junit.AssumptionViolatedException;
import org.junit.rules.ExternalResource;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import zipkin2.CheckResult;
import zipkin2.elasticsearch.ElasticsearchStorage;
import zipkin2.elasticsearch.internal.client.RawContentLoggingClient;

public class ElasticsearchStorageRule extends ExternalResource {
  static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchStorageRule.class);
  static final int ELASTICSEARCH_PORT = 9200;
  final String image;
  final String index;
  GenericContainer container;
  Closer closer = Closer.create();

  public ElasticsearchStorageRule(String image, String index) {
    this.image = image;
    this.index = index;
  }

  @Override
  protected void before() {
    if (!"true".equals(System.getProperty("docker.skip"))) {
      try {
        LOGGER.info("Starting docker image " + image);
        container =
          new GenericContainer(image)
            .withExposedPorts(ELASTICSEARCH_PORT)
            .waitingFor(new HttpWaitStrategy().forPath("/"));
        container.start();
        if (Boolean.valueOf(System.getenv("ES_DEBUG"))) {
          container.followOutput(new Slf4jLogConsumer(LoggerFactory.getLogger(image)));
        }
        LOGGER.info("Starting docker image " + image);
      } catch (RuntimeException e) {
        LOGGER.warn("Couldn't start docker image " + image + ": " + e.getMessage(), e);
      }
    } else {
      LOGGER.info("Skipping startup of docker " + image);
    }

    try {
      tryToInitializeSession();
    } catch (RuntimeException | Error e) {
      if (container == null) throw e;
      LOGGER.warn("Couldn't connect to docker image " + image + ": " + e.getMessage(), e);
      container.stop();
      container = null; // try with local connection instead
      tryToInitializeSession();
    }
  }

  void tryToInitializeSession() {
    ElasticsearchStorage result = computeStorageBuilder().build();
    try {
      CheckResult check = result.check();
      if (!check.ok()) {
        throw new AssumptionViolatedException(check.error().getMessage(), check.error());
      }
    } finally {
      result.close();
    }
  }

  public ElasticsearchStorage.Builder computeStorageBuilder() {
    Consumer<ClientOptionsBuilder> customizer =
        Boolean.valueOf(System.getenv("ES_DEBUG"))
          ? client -> client
          .decorator(
            new LoggingClientBuilder()
              .requestLogLevel(LogLevel.WARN)
              .successfulResponseLogLevel(LogLevel.WARN)
              .failureResponseLogLevel(LogLevel.WARN)
              .newDecorator())
          .decorator(RawContentLoggingClient.newDecorator())
          : unused -> {};
    return ElasticsearchStorage.newBuilder()
        .clientCustomizer(customizer)
        .index(index)
        .flushOnWrites(true)
        .hosts(Arrays.asList(baseUrl()));
  }

  String baseUrl() {
    if (container != null && container.isRunning()) {
      return String.format(
          "http://%s:%d",
          container.getContainerIpAddress(), container.getMappedPort(ELASTICSEARCH_PORT));
    } else {
      // Use localhost if we failed to start a container (i.e. Docker is not available)
      return "http://localhost:" + ELASTICSEARCH_PORT;
    }
  }

  @Override
  protected void after() {
    try {
      closer.close();
    } catch (Exception | Error e) {
      LOGGER.warn("error closing session " + e.getMessage(), e);
    } finally {
      if (container != null) {
        LOGGER.info("Stopping docker image " + image);
        container.stop();
      }
    }
  }

  public static String index(TestName testName) {
    String result = testName.getMethodName().toLowerCase();
    return result.length() <= 48 ? result : result.substring(result.length() - 48);
  }
}
