package com.breadwallet.protocols.messageexchange;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.JobIntentService;
import android.util.Base64;
import android.util.Log;

import com.breadwallet.app.BreadApp;
import com.breadwallet.crypto.*;
import com.breadwallet.legacy.presenter.activities.ConfirmationActivity;
import com.breadwallet.protocols.messageexchange.entities.EncryptedMessage;
import com.breadwallet.protocols.messageexchange.entities.InboxEntry;
import com.breadwallet.protocols.messageexchange.entities.LinkMetaData;
import com.breadwallet.protocols.messageexchange.entities.MetaData;
import com.breadwallet.protocols.messageexchange.entities.PairingMetaData;
import com.breadwallet.tools.crypto.CryptoHelper;
import com.breadwallet.tools.manager.BRSharedPrefs;
import com.breadwallet.tools.security.BRKeyStore;
import com.breadwallet.tools.util.Utils;
import com.google.common.base.Optional;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.platform.interfaces.AccountMetaDataProvider;
import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static org.kodein.di.TypesKt.TT;

/**
 * BreadWallet
 * <p/>
 * Created by Mihail Gutan on <mihail@breadwallet.com> 7/12/18.
 * Copyright (c) 2018 breadwallet LLC
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
public final class MessageExchangeService extends JobIntentService {
    // TODO: As of release 4.0, this class is untested!

    private static final String TAG = MessageExchangeService.class.getSimpleName();

    private static final int JOB_ID = 0xcebbbf9b; // Used to identify jobs that belong to this service. (Random number used for uniqueness.)

    public static final String ACTION_REQUEST_TO_PAIR = "com.breadwallet.protocols.messageexchange.ACTION_REQUEST_TO_PAIR";
    public static final String ACTION_RETRIEVE_MESSAGES = "com.breadwallet.protocols.messageexchange.ACTION_RETRIEVE_MESSAGES";
    private static final String ACTION_PROCESS_PAIR_REQUEST = "com.breadwallet.protocols.messageexchange.ACTION_PROCESS_PAIR_REQUEST";
    public static final String ACTION_GET_USER_CONFIRMATION = "com.breadwallet.protocols.messageexchange.ACTION_GET_USER_CONFIRMATION";
    private static final String EXTRA_IS_USER_APPROVED = "com.breadwallet.protocols.messageexchange.EXTRA_IS_USER_APPROVED";
    public static final String EXTRA_METADATA = "com.breadwallet.protocols.messageexchange.EXTRA_METADATA";

    private static final int ENVELOPE_VERSION = 1;  // The current envelope version number for our message exchange protocol.
    private static final int NONCE_SIZE = 12; // Nonce size for our message exchange protocol.
    // TODO: We only support pairing with one service
    private static final String SERVICE_PWB = "PWB"; // Our service name for the feature known as Participate With BRD, Secure Checkout, etc.

    public static PairingMetaData mPairingMetaData;
    private static final Signer SIGNER_COMPACT = Signer.createForAlgorithm(Signer.Algorithm.COMPACT); // TODO: Need a safer way to access this that doesn't rely on underlying CryptoProviderAPI being init

    public enum MessageType {
        LINK,
        PING,
        PONG,
        ACCOUNT_REQUEST,
        ACCOUNT_RESPONSE
    }

    /**
     * Handles intents passed to the {@link MessageExchangeService} by creating a new worker thread to complete the work required.
     *
     * @param intent The intent specifying the work that needs to be completed.
     */
    @Override
    protected void onHandleWork(@NotNull Intent intent) {
        Log.d(TAG, "onHandleWork()");
        final String action = intent.getAction();
        if (action != null) {
            Log.d(TAG, "Intent Action -> " + action);

            switch (action) {
                case ACTION_REQUEST_TO_PAIR:
                    // User scanned QR, to initiate pairing with a remote wallet,
                    savePairingMetaDataToKvStore((PairingMetaData) intent.getParcelableExtra(EXTRA_METADATA));
                    // Show more service details about the pairing and ask the user to confirm.
                    MetaData linkMetaData = new LinkMetaData(MessageExchangeNetworkHelper.getService(this, mPairingMetaData.getService()));
                    confirmRequest(linkMetaData);
                    break;
                case ACTION_PROCESS_PAIR_REQUEST:
                    // User has approved or denied the pairing request after reviewing the details.
                    pair(intent.getBooleanExtra(EXTRA_IS_USER_APPROVED, false));
                    break;
                case ACTION_RETRIEVE_MESSAGES:
                    retrieveInboxEntries(this);
                    break;
                default:
                    Log.d(TAG, "Intent not recognized.");
            }
        }
    }

    /**
     * Adds work to the {@link }MessageExchangeService} work queue. See {@link #onHandleWork(Intent)}
     * for types of work that can be done in this service.
     *
     * @param context The context in which we are operating.
     * @param work    The intent containing the work that needs to be done.
     */
    public static void enqueueWork(Context context, Intent work) {
        enqueueWork(context, MessageExchangeService.class, JOB_ID, work);
    }

    /**
     * Creates an intent to start the {@link MessageExchangeService}.
     *
     * @param context The context in which we are operation.
     * @param action  The action to specify when starting the service.
     * @return The intent to start the {@link MessageExchangeService}.
     */
    public static Intent createIntent(Context context, String action) {
        Intent intent = new Intent(context, MessageExchangeService.class);
        intent.setAction(action);
        return intent;
    }

    /**
     * Creates an intent to start the {@link MessageExchangeService}.
     *
     * @param context    The context in which we are operation.
     * @param action     The action to specify when starting the service.
     * @param parcelable The parcelable containing some metadata about starting the service.
     * @return The intent to start the {@link MessageExchangeService}.
     */
    public static Intent createIntent(Context context, String action, Parcelable parcelable) {
        Intent intent = new Intent(context, MessageExchangeService.class);
        intent.setAction(action)
                .putExtra(EXTRA_METADATA, parcelable);
        return intent;
    }

    /**
     * Creates an intent with the specified parameters for the {@link ConfirmationActivity} to start this service.
     * Used for: Link.
     *
     * @param context        The context in which we are operating.
     * @param isUserApproved True, if the user approved the pending request; false, otherwise.
     * @return An intent with the specified parameters.
     */
    public static Intent createIntent(Context context, boolean isUserApproved) {
        Intent intent = new Intent(context, MessageExchangeService.class);
        intent.setAction(ACTION_PROCESS_PAIR_REQUEST)
                .putExtra(EXTRA_IS_USER_APPROVED, isUserApproved);
        return intent;
    }

    /**
     * Pairs the device to the remove wallet.
     *
     * @param isUserApproved True, if the user approved the pairing; false, otherwise.
     */
    public void pair(boolean isUserApproved) {
        byte[] ephemeralKey = Coder.createForAlgorithm(Coder.Algorithm.HEX).decode(mPairingMetaData.getPublicKeyHex()).orNull();

        Key pairingKey = getPairingKey();

        ByteString message;
        if (isUserApproved) {
            // Open the browser with the return url
            openUrl(mPairingMetaData.getReturnUrl());

            // The user has approved, send a link message containing the local entity's public key and id.
            message = createLink(ByteString.copyFrom(pairingKey.encodeAsPublic()), ByteString.copyFrom(BRSharedPrefs.getWalletRewardId().getBytes()));

            // Register our key with the server.
            MessageExchangeNetworkHelper.sendAssociatedKey(this, pairingKey.encodeAsPublic());
        } else {
            // The user has denied the request, send a link message containing an error code.
            message = createLink(Protos.Error.USER_DENIED);
        }

        EncryptedMessage encryptedMessage = encrypt(pairingKey, ephemeralKey, message.toByteArray());
        ByteString encryptedMessageByteString = ByteString.copyFrom(encryptedMessage.getEncryptedData());
        Protos.Envelope envelope = createEnvelope(encryptedMessageByteString, MessageType.LINK, ByteString.copyFrom(pairingKey.encodeAsPublic()),
                ByteString.copyFrom(ephemeralKey), BRSharedPrefs.getDeviceId(), ByteString.copyFrom(encryptedMessage.getNonce()));
        //TODO: This should not use getDeviceId... it should be a new UUID that we save for reference when message is replied to.

        byte[] signature = SIGNER_COMPACT.sign(CryptoHelper.doubleSha256(envelope.toByteArray()), pairingKey).get();
        envelope = envelope.toBuilder().setSignature(ByteString.copyFrom(signature)).build();
        Log.d(TAG, "pair: request envelope:" + envelope.toString());

        // Send link request to remote wallet.
        MessageExchangeNetworkHelper.sendEnvelope(this, envelope.toByteArray());
    }

    /**
     * Starts a browser activity with the specified URL.
     *
     * @param url The URL to open in the browser.
     */
    private void openUrl(String url) {
        if (!Utils.isNullOrEmpty(url)) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            Log.e(TAG, "openUrl: returnUrl is null!");
        }
    }

    /**
     * Retrieves the latest batch of messages from the server.
     *
     * @param context The context in which we are operating.
     */
    private void retrieveInboxEntries(Context context) {
        String lastProcessedCursor = getLastProcessedCursor(); //TODO: remove
        List<InboxEntry> inboxEntries = MessageExchangeNetworkHelper.fetchInbox(context, lastProcessedCursor);
        if (inboxEntries == null) {
            Log.e(TAG, "retrieveInboxEntries: inboxEntries is null");
            return;
        }
        int numOfInboxEntries = inboxEntries.size();
        Log.d(TAG, "retrieveInboxEntries: " + numOfInboxEntries);

// TODO: CHECK THESE
//        int version = requestEnvelope.getVersion();
//        String service = requestEnvelope.getService();

        if (numOfInboxEntries > 0) {
            List<String> cursors = new ArrayList<>();
            for (InboxEntry inboxEntry : inboxEntries) {
                Protos.Envelope envelope = getEnvelopeFromInboxEntry(inboxEntry);
                if (mPairingMetaData == null) {
                    PairingMetaData pairingMetaData = getPairingMetaData(envelope.getSenderPublicKey().toByteArray());
                    if (pairingMetaData == null) {
                        Log.e(TAG, "retrieveInboxEntries: pairingMetaData is null!");
                        break;
                    }
                }
                String cursor = inboxEntry.getCursor();

                cursors.add(cursor);
                //TODO: temp hack for server bug
                Log.e(TAG, "retrieveInboxEntries: cursor: " + cursor);
                Log.e(TAG, "retrieveInboxEntries: lastProcessedCursor: " + lastProcessedCursor);
                if (lastProcessedCursor != null && lastProcessedCursor.equals(cursor)) {
                    Log.e(TAG, "Received last cursor message when not expecting it. ");
                } else {
                    if (verifyEnvelopeSignature(envelope)) {
                        processEnvelope(envelope);

                    } else {
                        Log.e(TAG, "retrieveInboxEntries: signature verification failed. id: " + cursor);
                    }
                }
            }

            if (cursors.size() > 0) {
                MessageExchangeNetworkHelper.sendAck(context, cursors);
                setLastProcessedCursor(cursors.get(cursors.size() - 1));
            }
        }
    }

    /**
     * Get the {@link Protos.Envelope} from the {@link InboxEntry}
     *
     * @param inboxEntry The inbox entry to retrieve the envelope from.
     * @return The message envelope.
     */
    private Protos.Envelope getEnvelopeFromInboxEntry(InboxEntry inboxEntry) {
        try {
            byte[] envelopeData = Base64.decode(inboxEntry.getMessage(), Base64.NO_WRAP);
            return Protos.Envelope.parseFrom(envelopeData);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Error decoding envelope. InboxEntry cursor: " + inboxEntry.getCursor(), e);
            return null;
        }
    }

    /**
     * Processes the message within the specified envelope.
     *
     * @param envelope The envelope containing the message.
     */
    private void processEnvelope(Protos.Envelope envelope) {
        byte[] decryptedMessage = decryptEnvelope(envelope);
        MessageType messageType = MessageType.valueOf(envelope.getMessageType());

        try {
            switch (messageType) {
                case LINK:
                    processSyncRequest(messageType, envelope, decryptedMessage);
                    break;
                case PING:
                    processSyncRequest(messageType, envelope, decryptedMessage);
                    break;
                case ACCOUNT_REQUEST:
                    processSyncRequest(messageType, envelope, decryptedMessage);
                    break;
                default:
                    Log.e(TAG, "RequestMetaData type not recognized:" + messageType.name());
            }
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Error parsing protobuf for " + messageType.name() + "message.", e);
        }
    }

    /**
     * Processes synchronous messages.  These are messages that can be processed immediately upon receipt.
     *
     * @param messageType      The type of message.
     * @param requestEnvelope  The envelope containing the message to process along with metadata needed to send a response.
     * @param decryptedMessage The decrypted message.
     * @throws InvalidProtocolBufferException
     */
    private void processSyncRequest(MessageType messageType, Protos.Envelope requestEnvelope, byte[] decryptedMessage)
            throws InvalidProtocolBufferException {
        ByteString messageResponse;
        switch (messageType) {
            case LINK:
                processLink(requestEnvelope, decryptedMessage);
                return; // TODO: Returning for now because processLink sends a response if needed. Refactor so message response is sent back here
            case PING:
                Protos.Ping ping = Protos.Ping.parseFrom(decryptedMessage);
                messageResponse = Protos.Pong.newBuilder().setPong(ping.getPing()).build().toByteString();
                break;
            case ACCOUNT_REQUEST:
                messageResponse = processAccountRequest(decryptedMessage);
                break;
            default:
                // Should never happen because unknown message type is handled by the caller (processRequest()).
                throw new IllegalArgumentException("Request type unknown: " + messageType.name());
        }

        sendResponse(requestEnvelope, messageResponse);
    }

    /**
     * Retrieves pairing metadata which is needed for the encrypted message exchange for encrypting and
     * decrypting messages.
     *
     * @return The pairing meta data.
     */
    private PairingMetaData getPairingMetaData(byte[] publicKey) {
        if (mPairingMetaData == null) {
            mPairingMetaData = getPairingMetaDataFromKvStore(publicKey);
        }
        return mPairingMetaData;
    }

    public static PairingMetaData getPairingMetaDataFromKvStore(byte[] publicKey) {
        AccountMetaDataProvider metadataProvider =
                BreadApp.getKodeinInstance().Instance(TT(AccountMetaDataProvider.class), null);
        return metadataProvider.getPairingMetadata(publicKey);
    }

    /**
     * Sets pairing metadata which is needed for the encrypted message exchange for encrypting and
     * decrypting messages.
     *
     * @param pairingMetaData The pairing meta data.
     */
    private void savePairingMetaDataToKvStore(PairingMetaData pairingMetaData) {
        if (Utils.isNullOrEmpty(pairingMetaData.getService())) {
            pairingMetaData.setService(SERVICE_PWB);
        }

        mPairingMetaData = pairingMetaData;
        AccountMetaDataProvider metadataProvider =
                BreadApp.getKodeinInstance().Instance(TT(AccountMetaDataProvider.class), null);
        metadataProvider.putPairingMetadata(pairingMetaData);
    }

    private String getLastProcessedCursor() {
        AccountMetaDataProvider metadataProvider =
                BreadApp.getKodeinInstance().Instance(TT(AccountMetaDataProvider.class), null);
        return metadataProvider.getLastCursor();
    }


    private void setLastProcessedCursor(String lastProcessedCursor) {
        Log.e(TAG, "setLastProcessedCursor: " + lastProcessedCursor);
        if (!Utils.isNullOrEmpty(lastProcessedCursor)) {
            AccountMetaDataProvider metadataProvider =
                    BreadApp.getKodeinInstance().Instance(TT(AccountMetaDataProvider.class), null);
            metadataProvider.putLastCursor(lastProcessedCursor);
        }
    }

    /**
     * Generates the remote entity's pairing key which is needed for the encrypted message exchange for encrypting and
     * decrypting messages.
     * <p>
     * The is generated using the local entity's authentication and the remote entity's id.
     *
     * @return The remote entity's pairing key.
     */
    private Key getPairingKey() {
        byte[] authKey = BRKeyStore.getAuthKey();
        if (Utils.isNullOrEmpty(authKey) || Utils.isNullOrEmpty(mPairingMetaData.getId())) {
            Log.e(TAG, "getPairingKey: Auth key or sender id is null.");
            return null;
        }
        Optional<Key> optPrivateKey = Key.createFromPrivateKeyString(authKey);
        if (!optPrivateKey.isPresent()) {
            Log.e(TAG, "getPairingKey: Key.createFromPrivateKeyString failed with authkey.");
            return null;
        }
        return Key.createForPigeon(optPrivateKey.get(), mPairingMetaData.getId().getBytes()).orNull();
    }

    /**
     * Generates a random nonce.
     *
     * @return A random nonce.
     */
    private byte[] generateRandomNonce() {
        byte[] nonce = new byte[NONCE_SIZE];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    /**
     * Encrypts the specified message.
     *
     * @param pairingKey           The local entity's pairing key.
     * @param senderPublicKeyBytes The remote identity's public key.
     * @param message              The message to encrypt.
     * @return The encrypted message.
     */
    public EncryptedMessage encrypt(Key pairingKey, byte[] senderPublicKeyBytes, byte[] message) {
        Key senderPublicKey = Key.createFromPublicKeyString(senderPublicKeyBytes).get();
        byte[] nonce = generateRandomNonce();
        Cipher cipher = Cipher.createForPigeon(pairingKey, senderPublicKey, nonce);
        return new EncryptedMessage(cipher.encrypt(message).get(), nonce);
    }

    /**
     * Decrypts the message within the specified envelope.
     *
     * @param envelope The envelope containing the message to decrypt.
     * @return The decrypted message.
     */
    public byte[] decryptEnvelope(Protos.Envelope envelope) {
        Key pairingKey = getPairingKey();
        return decrypt(pairingKey, envelope.getSenderPublicKey().toByteArray(),
                envelope.getEncryptedMessage().toByteArray(), envelope.getNonce().toByteArray());
    }

    /**
     * @param pairingKey           The key to decrypt with
     * @param senderPublicKeyBytes Sender public key
     * @param encryptedMessage     The message to decrypt
     * @param nonce                The nonce used to encrypt the message
     * @return The decrypted message bytes
     */
    @VisibleForTesting
    protected byte[] decrypt(Key pairingKey, byte[] senderPublicKeyBytes, byte[] encryptedMessage, byte[] nonce) {
        Key senderPublicKey = Key.createFromPublicKeyString(senderPublicKeyBytes).get();
        Cipher cipher = Cipher.createForPigeon(pairingKey, senderPublicKey, nonce);
        return cipher.decrypt(encryptedMessage).get();
    }

    /**
     * Verifies the specified envelope's signature.
     *
     * @param envelope The envelope whose signature will be verified.
     * @return True, if the signature is valid; false, otherwise.
     */
    public boolean verifyEnvelopeSignature(Protos.Envelope envelope) {
        byte[] signature = envelope.getSignature().toByteArray();
        byte[] senderPublicKey = envelope.getSenderPublicKey().toByteArray();
        envelope = envelope.toBuilder().setSignature(ByteString.EMPTY).build();
        byte[] envelopeDoubleSha256 = CryptoHelper.doubleSha256(envelope.toByteArray());
        if (Utils.isNullOrEmpty(signature)) {
            Log.e(TAG, "verifyEnvelope: signature missing.");
            return false;
        }
        Optional<? extends Key> recoveredKeyOpt = SIGNER_COMPACT.recover(envelopeDoubleSha256, signature);
        if (recoveredKeyOpt.isPresent()) {
            return Arrays.equals(senderPublicKey, recoveredKeyOpt.get().encodeAsPublic());
        }
        return false;
    }

    /**
     * Get the response message type for the specified request type.
     *
     * @param requestMessageType The request message type.
     * @return The response message type.
     */
    private MessageType getResponseMessageType(String requestMessageType) {
        switch (MessageType.valueOf(requestMessageType)) {
            case LINK:
                return MessageType.LINK;
            case PING:
                return MessageType.PONG;
            case ACCOUNT_REQUEST:
                return MessageType.ACCOUNT_RESPONSE;
        }
        return null;
    }

    /**
     * Creates the {@link Protos.Envelope} for the specified parameters.
     *
     * @param encryptedMessage
     * @param messageType
     * @param senderPublicKey
     * @param receiverPublicKey
     * @param identifier
     * @param nonce
     * @return The {@link Protos.Envelope} for the specified parameters.
     */
    public static Protos.Envelope createEnvelope(ByteString encryptedMessage, MessageType messageType,
                                                 ByteString senderPublicKey, ByteString receiverPublicKey,
                                                 String identifier, ByteString nonce) {
        Protos.Envelope envelope = Protos.Envelope.newBuilder()
                .setVersion(ENVELOPE_VERSION)
                .setMessageType(messageType.name())
                .setService(mPairingMetaData.getService())
                .setEncryptedMessage(encryptedMessage)
                .setSenderPublicKey(senderPublicKey)
                .setReceiverPublicKey(receiverPublicKey)
                .setIdentifier(identifier)
                .setSignature(ByteString.EMPTY)
                .setNonce(nonce)
                .build();
        return envelope;

    }

    /**
     * Ercypts the response message and wraps it in an {@link Protos.Envelope} then signs it.
     *
     * @param messageResponse The unencrypted response message.
     * @return The {@link Protos.Envelope} containing the encrypted response message.
     */
    private Protos.Envelope createResponseEnvelope(String identifier, ByteString senderPublicKey, String requestMessageType, ByteString messageResponse) {
        // Encrypt the response message.
        Key pairingKey = getPairingKey();
        EncryptedMessage responseEncryptedMessage = encrypt(pairingKey, senderPublicKey.toByteArray(), messageResponse.toByteArray());

        // Determine type for response.
        MessageType responseMessageType = getResponseMessageType(requestMessageType);

        // Create message envelope sans signature.
        Protos.Envelope responseEnvelope = createEnvelope(ByteString.copyFrom(responseEncryptedMessage.getEncryptedData()),
                responseMessageType, ByteString.copyFrom(pairingKey.encodeAsPublic()), senderPublicKey, identifier,
                ByteString.copyFrom(responseEncryptedMessage.getNonce()));

        // Sign message envelope.
        byte[] responseSignature = SIGNER_COMPACT.sign(CryptoHelper.doubleSha256(responseEnvelope.toByteArray()), pairingKey).get();

        // Add signature to message envelope.
        return responseEnvelope.toBuilder().setSignature(ByteString.copyFrom(responseSignature)).build();
    }

    private void sendResponse(Protos.Envelope requestEnvelope, ByteString messageResponse) {
        Protos.Envelope envelope = createResponseEnvelope(requestEnvelope.getIdentifier(), requestEnvelope.getSenderPublicKey(),
                requestEnvelope.getMessageType(), messageResponse);
        MessageExchangeNetworkHelper.sendEnvelope(this, envelope.toByteArray());
    }

    /**
     * Prompts the user to confirm the pending remote request.
     *
     * @param metaData The meta data related to the request.
     */
    private void confirmRequest(MetaData metaData) {
        Intent intent = new Intent(this, ConfirmationActivity.class);
        intent.setAction(ACTION_GET_USER_CONFIRMATION)
                .setFlags(FLAG_ACTIVITY_NEW_TASK);
        Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA_METADATA, metaData);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    /**
     * Creates the initial {@link MessageType.LINK} message to send to the remote entity for the purposes
     * of pairing.
     *
     * @param publicKey The local entity's public key.  The remote entity will use this encrypt messages to this entity.
     * @param id        The local entity's id.
     * @return The {@link MessageType.LINK} message.
     */
    private ByteString createLink(ByteString publicKey, ByteString id) {
        Protos.Link.Builder responseBuilder = Protos.Link.newBuilder();
        responseBuilder.setPublicKey(publicKey)
                .setId(id)
                .setStatus(Protos.Status.ACCEPTED);

        return responseBuilder.build().toByteString();
    }

    /**
     * Creates the initial {@link MessageType.LINK} message to send to the remote entity in the case of a failed
     * pairing.
     *
     * @param error The {@link Protos.Error} which specifies the error code.
     * @return The {@link MessageType.LINK} message.
     */
    private ByteString createLink(Protos.Error error) {
        Protos.Link.Builder responseBuilder = Protos.Link.newBuilder();
        responseBuilder.setStatus(Protos.Status.REJECTED)
                .setError(error);

        return responseBuilder.build().toByteString();
    }

    /**
     * Processes the {@link MessageType.LINK} message that was sent by the remote entity in response to the initial
     * {@link MessageType.LINK}  message sent by this entity.
     *
     * @param envelope         The envelope containing the message.
     * @param decryptedMessage The decrypted message.
     * @throws InvalidProtocolBufferException If the decrypted message is malformed.
     */
    private void processLink(Protos.Envelope envelope, byte[] decryptedMessage) throws InvalidProtocolBufferException {
        //TODO shouldn't we also check the status of the message since it is a response to our initial message?
        Protos.Link link = Protos.Link.parseFrom(decryptedMessage);
        String senderId = mPairingMetaData.getId();
        if (senderId == null || !senderId.equals(link.getId().toStringUtf8())) {
            // If the response for the remote entity doesn't have the same id as the initial pairing request, reject.
            sendResponse(envelope, createLink(Protos.Error.REMOTE_ID_MISMATCH));
        } else {
            savePairingMetaDataToKvStore(mPairingMetaData);

        }
    }

    /**
     * Process the {@link MessageType.ACCOUNT_REQUEST} that was sent by the remote entity to retrieve more information
     * about the local entity.
     *
     * @param decryptedMessage The decrypted request message.
     * @return The response message.
     */
    private ByteString processAccountRequest(byte[] decryptedMessage) throws InvalidProtocolBufferException {
        Protos.AccountRequest request = Protos.AccountRequest.parseFrom(decryptedMessage);
        String currencyCode = request.getScope();

        Protos.AccountResponse.Builder responseBuilder = Protos.AccountResponse.newBuilder();
        Wallet wallet = null; // TODO: get wallet for currency code
        if (wallet == null) {
            responseBuilder.setStatus(Protos.Status.REJECTED)
                    .setError(Protos.Error.SCOPE_UNKNOWN);
        } else {
            String address = wallet.getTarget().toString();
            if (address == null) {
                responseBuilder.setStatus(Protos.Status.REJECTED)
                        .setError(Protos.Error.NO_ADDRESS_FOUND);
            } else {
                responseBuilder.setScope(currencyCode)
                        .setAddress(address)
                        .setStatus(Protos.Status.ACCEPTED);
            }
        }

        return responseBuilder.build().toByteString();
    }
}
