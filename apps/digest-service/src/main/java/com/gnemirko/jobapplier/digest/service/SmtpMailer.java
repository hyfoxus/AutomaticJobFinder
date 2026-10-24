package com.gnemirko.jobapplier.digest.service;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.gnemirko.jobapplier.digest.dto.Digest;
import com.gnemirko.jobapplier.digest.exception.DeliveryException;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/** Sends one multipart (text and HTML) mail per digest. Created by the configuration, in smtp mode only. */
public class SmtpMailer implements DigestMailer {

    private final JavaMailSender sender;
    private final String from;
    private final InternetAddress[] to;

    public SmtpMailer(JavaMailSender sender, String from, InternetAddress[] to) {
        this.sender = sender;
        this.from = from;
        this.to = to;
    }

    @Override
    public void deliver(Digest digest) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(digest.subject());
            helper.setText(digest.text(), digest.html());
            sender.send(message);
        } catch (Exception e) {
            throw new DeliveryException("mail was not accepted: " + e.getClass().getSimpleName(), e);
        }
    }
}
