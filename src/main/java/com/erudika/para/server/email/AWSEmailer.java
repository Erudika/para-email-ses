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
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest;
import software.amazon.awssdk.services.ses.model.SendRawEmailResponse;

/**
 * An emailer that uses AWS Simple Email Service (SES).
 * By default, this implementation treats the body as HTML content.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class AWSEmailer implements Emailer {

	private final SesClient sesclient;

	/**
	 * No-args constructor.
	 */
	public AWSEmailer() {
		sesclient = SesClient.builder().
				// AWS SES is not available in all regions and it's best if we set it manually
				region(Region.of(Para.getConfig().awsSesRegion())).build();
	}

	@Override
	public boolean sendEmail(List<String> emails, String subject, String body) {
		return sendEmail(emails, subject, body, null, null, null);
	}

	@Override
	public boolean sendEmail(List<String> emails, String subject, String body, InputStream attachment, String mimeType, String fileName) {
		if (emails == null || emails.isEmpty()) {
			return false;
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
			if (attachment != null && !StringUtils.isBlank(mimeType)) {
				byte[] fileByteArray = attachment.readAllBytes();
				InternetHeaders fileHeaders = new InternetHeaders();
				fileHeaders.setHeader("Content-Type", mimeType + "; name=\"" + fileName + "\"");
				fileHeaders.setHeader("Content-Transfer-Encoding", "base64");
				fileHeaders.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");

				MimeBodyPart attach = new MimeBodyPart(fileHeaders, fileByteArray);
				ByteArrayDataSource ds = new ByteArrayDataSource(fileByteArray, mimeType);
				attach.setDataHandler(new DataHandler(ds));
				attach.setFileName(fileName);

				msg.addBodyPart(attach);
			}

			try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
				message.setContent(msg);
				message.writeTo(outputStream);
				SendRawEmailRequest rawEmailRequest = SendRawEmailRequest.builder().
						rawMessage(r -> r.data(SdkBytes.fromByteArray(outputStream.toByteArray()))).build();
				SendRawEmailResponse res = sesclient.sendRawEmail(rawEmailRequest);
				return res.sdkHttpResponse().isSuccessful();
			}
			// Display an error if something goes wrong.
		} catch (Exception ex) {
			LoggerFactory.getLogger(AWSEmailer.class).error(null, ex);
		}
		return false;
	}


}
