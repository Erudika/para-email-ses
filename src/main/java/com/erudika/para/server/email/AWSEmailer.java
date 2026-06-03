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

import com.erudika.para.core.email.Emailer;
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

	private final SesAsyncClient sesclient;

	/**
	 * No-args constructor.
	 */
	public AWSEmailer() {
		sesclient = SesAsyncClient.builder().
				// AWS SES is not available in all regions and it's best if we set it manually
				region(Region.of(Para.getConfig().awsSesRegion())).build();
	}

	@Override
	public void sendSingleBatch(List<String> emails, String subject, String body, ByteArrayDataSource attachment, String fileName) {
		if (emails == null || emails.isEmpty()) {
			return;
		}

		if (StringUtils.isBlank(body)) {
			body = "(blank)";
		}

		try {
			Session session = Session.getDefaultInstance(new Properties());
			MimeMessage message = new MimeMessage(session);
			message.setSubject(subject, "UTF-8");
			message.setFrom(new InternetAddress(Para.getConfig().supportEmail(), Para.getConfig().appName()));
			Iterator<String> emailz = emails.iterator();
			message.setRecipients(RecipientType.TO, InternetAddress.parse(emailz.next()));
			StringBuilder sb = new StringBuilder();
			while (emailz.hasNext()) {
				sb.append(emailz.next()).append(emailz.hasNext() ? "," : "");
			}
			message.setRecipients(RecipientType.BCC, InternetAddress.parse(sb.toString()));

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
				sesclient.sendRawEmail(rawEmailRequest);
			}
			// Display an error if something goes wrong.
		} catch (Exception ex) {
			logger.error(null, ex);
		}
	}

}
