/*
 * Copyright 2016 the original author or authors.
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

package org.springframework.integration.aws.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.aws.support.AwsHeaders;
import org.springframework.integration.aws.support.SnsBodyBuilder;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sns.model.PublishResult;

/**
 * @author Artem Bilan
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration
@DirtiesContext
public class SnsMessageHandlerTests {

	private static SpelExpressionParser PARSER = new SpelExpressionParser();

	@Autowired
	private MessageChannel sendToSnsChannel;

	@Autowired
	private AmazonSNS amazonSNS;

	@Test
	public void testSnsMessageHandler() {
		SnsBodyBuilder payload = SnsBodyBuilder.withDefault("foo")
				.forProtocols("{\"foo\" : \"bar\"}", "sms");

		QueueChannel replyChannel = new QueueChannel();

		Message<?> message = MessageBuilder.withPayload(payload)
				.setHeader("topic", "topic")
				.setHeader("subject", "subject")
				.setReplyChannel(replyChannel)
				.build();

		this.sendToSnsChannel.send(message);

		Message<?> reply = replyChannel.receive(1000);
		assertThat(reply).isNotNull();

		ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
		verify(this.amazonSNS).publish(captor.capture());

		PublishRequest publishRequest = captor.getValue();

		assertThat(publishRequest.getMessageStructure()).isEqualTo("json");
		assertThat(publishRequest.getTopicArn()).isEqualTo("topic");
		assertThat(publishRequest.getSubject()).isEqualTo("subject");
		assertThat(publishRequest.getMessage())
				.isEqualTo("{\"default\":\"foo\",\"sms\":\"{\\\"foo\\\" : \\\"bar\\\"}\"}");

		assertThat(reply.getHeaders().get(AwsHeaders.SNS_PUBLISHED_MESSAGE_ID)).isEqualTo("111");
		assertThat(reply.getHeaders().get(AwsHeaders.TOPIC)).isEqualTo("topic");
		assertThat(reply.getPayload()).isSameAs(publishRequest);
	}

	@Configuration
	@EnableIntegration
	public static class ContextConfiguration {

		@Bean
		public AmazonSNS amazonSNS() {
			AmazonSNS mock = mock(AmazonSNS.class);

			willAnswer(invocation -> new PublishResult().withMessageId("111"))
					.given(mock)
					.publish(any(PublishRequest.class));

			return mock;
		}

		@Bean
		@ServiceActivator(inputChannel = "sendToSnsChannel")
		public MessageHandler snsMessageHandler() {
			SnsMessageHandler snsMessageHandler = new SnsMessageHandler(amazonSNS(), true);
			snsMessageHandler.setTopicArnExpression(PARSER.parseExpression("headers.topic"));
			snsMessageHandler.setSubjectExpression(PARSER.parseExpression("headers.subject"));
			snsMessageHandler.setBodyExpression(PARSER.parseExpression("payload"));
			return snsMessageHandler;
		}

	}

}
