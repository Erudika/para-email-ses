/*
 * Copyright 2013-2026 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.server.email;

import com.erudika.para.core.App;
import com.erudika.para.core.email.Emailer;
import static com.erudika.para.core.email.Emailer.logger;
import com.erudika.para.core.utils.Para;
import com.erudika.para.core.utils.Utils;
import jakarta.activation.DataHandler;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesAsyncClient;
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest;

/**
 * An emailer that uses AWS Simple Email Service (SES).
 * By default, this implementation treats the body as HTML content.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class AWSEmailer implements Emailer {

	private SesAsyncClient sesclient;

	private Emailer defaultFallback;

	/**
	 * No-args constructor.
	 */
	public AWSEmailer() {
	}

	@Override
	public void setDefaultFallback(Emailer emailer) {
		defaultFallback = emailer;
	}

	private SesAsyncClient getEmailer(App app) {
		if (app == null) {
			return buildClient();
		} else {
			String host = Para.getConfig().getSettingForApp(app, "mail.host", "");
			String accessKey = Para.getConfig().getSettingForApp(app, "mail.username", "");
			String secretKey = Para.getConfig().getSettingForApp(app, "mail.password", "");
			if (StringUtils.isBlank(host) || StringUtils.isBlank(accessKey) || StringUtils.isBlank(secretKey)) {
				return buildClient();
			} else {
				return SesAsyncClient.builder().
						region(getRegionFromHost(host, app)).
						credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))).
						build();
			}
		}
	}

	private SesAsyncClient buildClient() {
		if (sesclient == null) {
			sesclient = SesAsyncClient.builder().
					// AWS SES is not available in all regions and it's best if we set it manually
					region(Region.of(Para.getConfig().awsSesRegion())).build();
		}
		return sesclient;
	}

	private static Region getRegionFromHost(String host, App app) {
		String hostname = host;
		int schemeEnd = hostname.indexOf("://");
		if (schemeEnd >= 0) {
			hostname = hostname.substring(schemeEnd + 3);
		}
		hostname = StringUtils.substringBefore(hostname, "/");
		hostname = StringUtils.substringBeforeLast(hostname, ":");

		String defaultRegion = Para.getConfig().awsSesRegion();
		String awsSuffix = ".amazonaws.";
		int suffixStart = hostname.indexOf(awsSuffix);
		if (suffixStart <= 0) {
			logger.warn("Unable to derive AWS region from `mail.host` for app {}. Using {}.", app.getId(), defaultRegion);
			return Region.of(defaultRegion);
		}

		String serviceAndRegion = hostname.substring(0, suffixStart);
		int separator = serviceAndRegion.indexOf('.');
		if (separator <= 0 || separator == serviceAndRegion.length() - 1) {
			logger.warn("Unable to derive AWS region from `mail.host` for app {}. Using {}.", app.getId(), defaultRegion);
			return Region.of(defaultRegion);
		}
		return Region.of(serviceAndRegion.substring(separator + 1));
	}

	private static boolean isSmtpHost(String host) {
		String hostname = host.trim();
		int schemeEnd = hostname.indexOf("://");
		if (schemeEnd >= 0) {
			hostname = hostname.substring(schemeEnd + 3);
		}
		hostname = StringUtils.substringBefore(hostname, "/");
		return hostname.regionMatches(true, 0, "email-smtp.", 0, "email-smtp.".length());
	}

	private void sendWithJavaMail(App app, List<String> emails, String subject, String body,
			ByteArrayDataSource attachment, String fileName) throws Exception {
		if (defaultFallback != null) {
			defaultFallback.sendSingleBatch(app, emails, subject, body, attachment, fileName);
		} else {
			logger.error("Default fallback implementation for Emailer not set.");
		}
	}

	@Override
	public void sendSingleBatch(App app, List<String> emails, String subject, String body, ByteArrayDataSource attachment, String fileName) {
		if (emails == null || emails.isEmpty()) {
			return;
		}

		if (StringUtils.isBlank(body)) {
			body = "(blank)";
		}

		try {
			if (app != null) {
				String host = Para.getConfig().getSettingForApp(app, "mail.host", "");
				if (isSmtpHost(host)) {
					logger.debug("Sending email '{}' using JavaMail SMTP", subject);
					sendWithJavaMail(app, emails, subject, body, attachment, fileName);
					return;
				}
			}
			Session session = Session.getDefaultInstance(new Properties());
			MimeMessage message = new MimeMessage(session);
			message.setSubject(subject, "UTF-8");
			message.setFrom(new InternetAddress(getFromEmail(app), getFromName(app)));
			if (emails.size() > 1) {
				StringBuilder sb = new StringBuilder();
				Iterator<String> emailz = emails.iterator();
				while (emailz.hasNext()) {
					sb.append(emailz.next()).append(emailz.hasNext() ? "," : "");
				}
				message.setRecipients(RecipientType.BCC, InternetAddress.parse(sb.toString()));
				String to = "noreply@" + StringUtils.substringAfter(getFromEmail(app), "@");
				message.setRecipients(RecipientType.TO, InternetAddress.parse(to));
			} else {
				message.setRecipients(RecipientType.TO, InternetAddress.parse(emails.iterator().next()));
			}
			MimeMultipart msgBody = new MimeMultipart("alternative");
			MimeBodyPart bodyWrapper = new MimeBodyPart();

			// Define the text part
			MimeBodyPart textPart = new MimeBodyPart();
			textPart.setContent(Utils.stripHtml(body), "text/plain; charset=UTF-8");

			// Define the HTML part
			MimeBodyPart htmlPart = new MimeBodyPart();
			htmlPart.setContent(body, "text/html; charset=UTF-8");

			msgBody.addBodyPart(textPart);
			msgBody.addBodyPart(htmlPart);
			bodyWrapper.setContent(msgBody);

			MimeMultipart msg = new MimeMultipart("mixed");
			msg.addBodyPart(bodyWrapper);

			// File part
			if (attachment != null && !StringUtils.isBlank(attachment.getContentType())) {
//				byte[] fileByteArray = attachment.readAllBytes();
				MimeBodyPart attach = new MimeBodyPart(attachment.getInputStream());
				attach.setHeader("Content-Type", attachment.getContentType() + "; name=\"" + fileName + "\"");
				attach.setHeader("Content-Transfer-Encoding", "base64");
				attach.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
				attach.setDataHandler(new DataHandler(attachment));
				attach.setFileName(fileName);

				msg.addBodyPart(attach);
			}
			logger.debug("Sending email '{}' to {} recipients, {}", subject, emails.size());
			try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
				message.setContent(msg);
				message.writeTo(outputStream);
				SendRawEmailRequest rawEmailRequest = SendRawEmailRequest.builder().
						rawMessage(r -> r.data(SdkBytes.fromByteArray(outputStream.toByteArray()))).build();
				getEmailer(app).sendRawEmail(rawEmailRequest).whenComplete((response, error) -> {
					if (error != null) {
						logger.error("Failed to send email '{}'", subject, error);
					} else {
						logger.debug("Email '{}' accepted by SES with message ID {}", subject, response.messageId());
					}
				});
			}
			// Display an error if something goes wrong.
		} catch (Exception ex) {
			logger.error(null, ex);
		}
	}

}
