package com.guildworkman.api.signing.service;

import com.guildworkman.api.signing.model.ChannelAccount;
import com.guildworkman.api.signing.model.ChannelAccountStatus;
import com.guildworkman.api.signing.repository.ChannelAccountRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Operator-facing management of the channel-account pool: registering
 * accounts, taking one out of service, putting it back.
 *
 * <p>An account is registered by <b>key reference</b>, never by account id.
 * The public key is then resolved through the active
 * {@code SigningProvider}, which makes an unsignable account impossible to
 * register: if custody can't produce a public key for the reference, there is
 * nothing to add to the pool. It also means the same operator request works
 * unchanged against a local seed in development and an HSM in production.
 *
 * <p>New accounts start {@link ChannelAccountStatus#NEEDS_RESYNC} rather than
 * {@code AVAILABLE}: the pool has no idea what the account's sequence number
 * is until it reads it from the network, and the first lease does exactly
 * that. Registration therefore needs no network round-trip and can't be left
 * half-done by an RPC outage.
 */
@Service
@RequiredArgsConstructor
public class ChannelAccountService {

    private static final Logger log = LoggerFactory.getLogger(ChannelAccountService.class);

    private final ChannelAccountRepository channelAccounts;
    private final TransactionSigner signer;

    @Transactional
    public ChannelAccount register(String keyRef) {
        String accountId = signer.publicKey(keyRef);

        channelAccounts.findByKeyRef(keyRef).ifPresent(existing -> {
            throw new ChannelAccountAlreadyRegisteredException(
                    "keyRef '" + keyRef + "' is already registered as channel account " + existing.getId());
        });
        channelAccounts.findByAccountId(accountId).ifPresent(existing -> {
            throw new ChannelAccountAlreadyRegisteredException(
                    "Account " + accountId + " is already registered as channel account " + existing.getId());
        });

        ChannelAccount account = new ChannelAccount();
        account.setAccountId(accountId);
        account.setKeyRef(keyRef);
        account.setStatus(ChannelAccountStatus.NEEDS_RESYNC);
        account.setNextSequence(0);
        ChannelAccount saved = channelAccounts.save(account);
        log.info("Channel account registered id={} accountId={} keyRef={}",
                saved.getId(), saved.getAccountId(), keyRef);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ChannelAccount> list() {
        return channelAccounts.findAll();
    }

    @Transactional(readOnly = true)
    public ChannelAccount get(Long id) {
        return channelAccounts.findById(id).orElseThrow(() -> new ChannelAccountNotFoundException(id));
    }

    /**
     * Takes an account out of the pool. A leased account is left leased —
     * disabling it under an in-flight transaction would strand that
     * transaction's sequence number — so the flag takes effect once the
     * current submission finishes with it.
     */
    @Transactional
    public ChannelAccount disable(Long id) {
        ChannelAccount account = get(id);
        if (account.getStatus() == ChannelAccountStatus.LEASED) {
            throw new ChannelAccountBusyException("Channel account " + id
                    + " is currently leased by submission " + account.getLeasedBySubmissionId()
                    + "; retry once that submission reaches a terminal state");
        }
        account.setStatus(ChannelAccountStatus.DISABLED);
        log.info("Channel account disabled id={} accountId={}", id, account.getAccountId());
        return channelAccounts.save(account);
    }

    /**
     * Returns a disabled account to the pool as {@code NEEDS_RESYNC}: however
     * long it sat out, its sequence has to be re-read before it's trusted.
     */
    @Transactional
    public ChannelAccount enable(Long id) {
        ChannelAccount account = get(id);
        if (account.getStatus() == ChannelAccountStatus.LEASED) {
            return account;
        }
        account.setStatus(ChannelAccountStatus.NEEDS_RESYNC);
        log.info("Channel account enabled id={} accountId={}", id, account.getAccountId());
        return channelAccounts.save(account);
    }
}
