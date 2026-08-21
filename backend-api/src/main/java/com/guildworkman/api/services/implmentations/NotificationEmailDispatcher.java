package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.config.AsyncConfig;
import com.guildworkman.api.data.constants.NotificationEmailStatus;
import com.guildworkman.api.data.repository.NotificationRepository;
import com.guildworkman.api.dto.requests.SendMailRequest;
import com.guildworkman.api.services.ServiceUtils.MailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Sends the transactional email behind one notification, off the request
 * thread and after the appointment transaction that produced it has already
 * committed (see {@code NotificationServiceImpl#scheduleEmail}).
 *
 * <p>A separate bean from {@code NotificationServiceImpl} is required for
 * {@code @Async} to actually apply: Spring's async proxy only intercepts
 * calls that arrive from <em>outside</em> the bean, so a self-invoked
 * {@code @Async} method on the same class would run synchronously.
 *
 * <p>{@code MailService} failures (a down or slow provider, a non-2xx
 * response) are caught here and never rethrown: this runs with nothing left
 * to observe the exception, and the whole point is that a mail failure must
 * not affect the appointment operation that already succeeded. The outcome is
 * instead recorded on the notification's {@code emailStatus}.
 */
@Service
@RequiredArgsConstructor
public class NotificationEmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailDispatcher.class);

    private final MailService mailService;
    private final NotificationRepository notificationRepository;

    @Async(AsyncConfig.NOTIFICATION_MAIL_EXECUTOR)
    public void dispatch(Long notificationId, String recipientEmail, String recipientName,
                          String subject, String htmlContent) {
        try {
            SendMailRequest request = new SendMailRequest();
            request.setRecipientEmail(recipientEmail);
            request.setRecipientName(recipientName);
            request.setSubject(subject);
            request.setContent(htmlContent);
            mailService.sendMail(request);
            markEmailStatus(notificationId, NotificationEmailStatus.SENT);
        } catch (Exception exception) {
            log.warn("Failed to send notification email (notificationId={}, recipient={}): {}",
                    notificationId, recipientEmail, exception.getMessage());
            markEmailStatus(notificationId, NotificationEmailStatus.FAILED);
        }
    }

    private void markEmailStatus(Long notificationId, NotificationEmailStatus status) {
        notificationRepository.findById(notificationId).ifPresent(notification -> {
            notification.setEmailStatus(status);
            notificationRepository.save(notification);
        });
    }
}
