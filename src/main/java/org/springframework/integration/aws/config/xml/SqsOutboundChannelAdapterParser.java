/*
 * Copyright 2015-2016 the original author or authors.
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

package org.springframework.integration.aws.config.xml;

import org.w3c.dom.Element;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.integration.aws.outbound.SqsMessageHandler;
import org.springframework.integration.config.xml.AbstractOutboundChannelAdapterParser;
import org.springframework.integration.config.xml.IntegrationNamespaceUtils;
import org.springframework.util.StringUtils;

/**
 * The parser for the {@code <int-aws:sqs-outbound-channel-adapter>}.
 *
 * @author Artem Bilan
 * @author Rahul Pilani
 */
public class SqsOutboundChannelAdapterParser extends AbstractOutboundChannelAdapterParser {

	@Override
	protected AbstractBeanDefinition parseConsumer(Element element, ParserContext parserContext) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition(SqsMessageHandler.class);

		String template = element.getAttribute(AwsParserUtils.QUEUE_MESSAGING_TEMPLATE_REF);
		boolean hasTemplate = StringUtils.hasText(template);
		String sqs = element.getAttribute(AwsParserUtils.SQS_REF);
		boolean hasSqs = StringUtils.hasText(sqs);
		String resourceIdResolver = element.getAttribute(AwsParserUtils.RESOURCE_ID_RESOLVER_REF);
		boolean hasResourceIdResolver = StringUtils.hasText(resourceIdResolver);
		if (hasTemplate && (hasSqs || hasResourceIdResolver)) {
			parserContext.getReaderContext().error(AwsParserUtils.QUEUE_MESSAGING_TEMPLATE_REF +
					" should not be defined in conjunction with " + AwsParserUtils.SQS_REF
					+ " or " + AwsParserUtils.RESOURCE_ID_RESOLVER_REF, element);
		}

		if (!hasTemplate && !hasSqs) {
			parserContext.getReaderContext().error("One of " + AwsParserUtils.QUEUE_MESSAGING_TEMPLATE_REF + " or "
					+ AwsParserUtils.SQS_REF + " must be defined.", element);
		}

		if (hasSqs) {
			builder.addConstructorArgReference(sqs);
		}
		if (hasResourceIdResolver) {
			builder.addConstructorArgReference(resourceIdResolver);
		}
		if (hasTemplate) {
			builder.addConstructorArgReference(template);
		}
		BeanDefinition queue = IntegrationNamespaceUtils.createExpressionDefinitionFromValueOrExpression("queue",
				"queue-expression", parserContext, element, false);
		if (queue != null) {
			builder.addPropertyValue("queueExpression", queue);
		}
		return builder.getBeanDefinition();
	}

}
