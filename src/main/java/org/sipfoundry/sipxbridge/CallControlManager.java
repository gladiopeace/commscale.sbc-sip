/*
 *  Copyright (C) 2008 Pingtel Corp., certain elements licensed under a Contributor Agreement.
 *  Contributors retain copyright to elements licensed under a Contributor Agreement.
 *  Licensed to the User under the LGPL license.
 *
 */
package org.sipfoundry.sipxbridge;

import gov.nist.javax.sip.DialogExt;
import gov.nist.javax.sip.SipStackImpl;
import gov.nist.javax.sip.header.ims.PAssertedIdentityHeader;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.stack.SIPDialog;
import gov.nist.javax.sip.stack.SIPServerTransaction;

import java.io.IOException;
import java.text.ParseException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.sdp.SdpFactory;
import javax.sdp.SessionDescription;
import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogState;
import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.TransactionAlreadyExistsException;
import javax.sip.TransactionState;
import javax.sip.address.SipURI;
import javax.sip.header.AcceptHeader;
import javax.sip.header.AcceptLanguageHeader;
import javax.sip.header.AllowHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.EventHeader;
import javax.sip.header.SubscriptionStateHeader;
import javax.sip.header.SupportedHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;
import org.sipfoundry.sipxbridge.symmitron.KeepaliveMethod;

/**
 * Processes INVITE, REFER, ACK and BYE
 * 
 * @author M. Ranganathan
 * 
 */
class CallControlManager {

    private static Logger logger = Logger.getLogger(CallControlManager.class);

    private ConcurrentHashMap<String, BackToBackUserAgent> backToBackUserAgentTable = new ConcurrentHashMap<String, BackToBackUserAgent>();

    /**
     * Send gateway internal error.
     */
    static void sendInternalError(ServerTransaction st, Exception ex) {
        try {
            Request request = st.getRequest();
            Response response = ProtocolObjects.messageFactory.createResponse(
                    Response.SERVER_INTERNAL_ERROR, request);
            if (logger.isDebugEnabled()) {
                String message = "Exception occured at " + ex.getMessage() + " at "
                        + ex.getStackTrace()[0].getFileName() + ":"
                        + ex.getStackTrace()[0].getLineNumber();

                response.setReasonPhrase(message);
            } else {
                response.setReasonPhrase(ex.getCause().getMessage());
            }
            st.sendResponse(response);

        } catch (Exception e) {
            throw new RuntimeException("Check gateway configuration", e);
        }
    }

    /**
     * Send Internal error to the other side.
     * 
     */
    static void sendBadRequestError(ServerTransaction st, Exception ex) {
        try {
            Request request = st.getRequest();
            Response response = ProtocolObjects.messageFactory.createResponse(
                    Response.BAD_REQUEST, request);
            if (logger.isDebugEnabled()) {
                String message = "Exception occured at " + ex.getMessage() + " at "
                        + ex.getStackTrace()[0].getFileName() + ":"
                        + ex.getStackTrace()[0].getLineNumber();

                response.setReasonPhrase(message);
            }
            st.sendResponse(response);

        } catch (Exception e) {
            throw new RuntimeException("Check gateway configuration", e);
        }
    }

    private void adjustSessionParameters(ServerTransaction serverTransaction, Request request,
            Dialog dialog, SipProvider provider, Dialog peerDialog) throws Exception {
        DialogApplicationData dat = (DialogApplicationData) dialog.getApplicationData();

        RtpSession lanRtpSession = dat.getRtpSession();
        SessionDescription newDescription = lanRtpSession.reAssignSessionParameters(request,
                serverTransaction, dialog, peerDialog);

        Response response = ProtocolObjects.messageFactory.createResponse(Response.OK, request);
        SupportedHeader sh = ProtocolObjects.headerFactory.createSupportedHeader("replaces");
        response.setHeader(sh);
        if (newDescription != null) {
            response.setContent(newDescription, ProtocolObjects.headerFactory
                    .createContentTypeHeader("application", "sdp"));
        }

        ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
        String userName = ((SipURI) toHeader.getAddress().getURI()).getUser();
        ContactHeader contactHeader = SipUtilities.createContactHeader(userName, provider, Gateway.getSipxProxyTransport());
        response.setHeader(contactHeader);
        response.setReasonPhrase("RTP Session Parameters Changed");

        if (serverTransaction != null) {
            serverTransaction.sendResponse(response);
        } else {
            provider.sendResponse(response);

        }
        return;
    }

    /**
     * Processes an incoming invite from the PBX or from the ITSP side.
     * 
     * 
     * @param requestEvent
     */
    private void processInvite(RequestEvent requestEvent) {

        Request request = requestEvent.getRequest();

        SipProvider provider = (SipProvider) requestEvent.getSource();
        ServerTransaction serverTransaction = requestEvent.getServerTransaction();

        try {

            if (serverTransaction == null) {
                try {
                    serverTransaction = provider.getNewServerTransaction(request);
                } catch (TransactionAlreadyExistsException ex) {
                    return;
                }
            }
            Dialog dialog = serverTransaction.getDialog();

            if (dialog.getState() == DialogState.CONFIRMED) {
                logger.debug("Re-INVITE proessing !! ");
                DialogApplicationData dat = (DialogApplicationData) dialog.getApplicationData();
                RtpSession rtpSession = dat.getRtpSession();
                Dialog peerDialog = dat.peerDialog;
                SipProvider peerDialogProvider = ((DialogExt) peerDialog).getSipProvider();

                // See if we need to re-INVITE MOH server. If the session
                // is already set up with right codec we do not need to do that.

                if (!dat.sendReInviteOnResume
                        && ((!Gateway.isReInviteSupported()) || Gateway.getMusicOnHoldAddress() == null)) {
                    // Can handle this request locally without re-Inviting server (optimization)

                    this.adjustSessionParameters(serverTransaction, request, dialog, provider,
                            null);
                    return;
                } else {
                    if (SipUtilities.isSdpQuery(request)) {
                        Request newRequest = peerDialog.createRequest(Request.INVITE);
                        ClientTransaction ctx = peerDialogProvider.getNewClientTransaction(newRequest);
                        TransactionApplicationData tad = new TransactionApplicationData(Operation.QUERY_SDP_FROM_PEER_DIALOG);
                        tad.serverTransaction = serverTransaction;
                        ctx.setApplicationData(tad);
                        peerDialog.sendRequest(ctx);                       
                        return;
                    } else if (rtpSession.isHoldRequest(request)) {
                        dat.sendReInviteOnResume = true;
                        ReInviteProcessingContinuationData continuationData = new ReInviteProcessingContinuationData(
                                dialog, provider, serverTransaction, request);
                        dat.getBackToBackUserAgent().querySdpFromPeerDialog(requestEvent,
                                Operation.SEND_INVITE_TO_MOH_SERVER, continuationData);
                        return;
                    } else {
                        /* Say BYE to MOH server and continue processing. */
                        dat.sendReInviteOnResume = false;
                        rtpSession.reAssignSessionParameters(request, serverTransaction, dialog,
                                null);

                        return;
                    }
                }

            }

            BackToBackUserAgent btobua = null;

            if ((DialogApplicationData) dialog.getApplicationData() != null) {
                btobua = ((DialogApplicationData) dialog.getApplicationData())
                        .getBackToBackUserAgent();
            } else {

                btobua = Gateway.getCallControlManager().getBackToBackUserAgent(provider,
                        request, dialog, Gateway.getLanProvider() == requestEvent.getSource());
                /*
                 * Make sure we know about the incoming request. Otherwise we return an error
                 * here.
                 */
                if (btobua == null) {
                    Response response = ProtocolObjects.messageFactory.createResponse(
                            Response.NOT_FOUND, request);
                    response.setReasonPhrase("Could not find account record for ITSP");
                    serverTransaction.sendResponse(response);
                    return;
                }

                DialogApplicationData.attach(btobua, dialog, request);
            }

            // This method was seen from the LAN side.
            // Create a WAN side association and send the INVITE on its way.
            if (provider == Gateway.getLanProvider()) {
                ItspAccountInfo account = Gateway.getAccountManager().getAccount(
                        (SipURI) request.getRequestURI());
                if (account == null) {
                    Response response = ProtocolObjects.messageFactory.createResponse(
                            Response.NOT_FOUND, request);
                    response.setReasonPhrase("Could not find account record for ITSP");
                    serverTransaction.sendResponse(response);
                    return;

                }
                // This case occurs when in and outbound proxy are different.
                btobua.setItspAccount(account);
                String toDomain = account.getSipDomain();

                boolean isPhone = ((SipURI) request.getRequestURI()).getParameter("user") != null
                        && ((SipURI) request.getRequestURI()).getParameter("user")
                                .equals("phone");
                btobua.sendInviteToItsp(requestEvent, serverTransaction, toDomain, isPhone);
            } else {

                btobua.sendInviteToSipxProxy(requestEvent, serverTransaction);

            }

        } catch (RuntimeException ex) {
            logger.error("Error processing request" + requestEvent.getRequest(), ex);
            sendInternalError(serverTransaction, ex);
        } catch (Exception ex) {
            logger.error("Error processing request " + requestEvent.getRequest(), ex);
            sendBadRequestError(serverTransaction, ex);
        }
    }

    /**
     * Process an OPTIONS request.
     * 
     */
    private void processOptions(RequestEvent requestEvent) {
        SipProvider provider = (SipProvider) requestEvent.getSource();
        Request request = requestEvent.getRequest();
        ServerTransaction st = requestEvent.getServerTransaction();

        try {

            Response response = ProtocolObjects.messageFactory.createResponse(Response.OK,
                    request);

            ContactHeader contactHeader = null;
            if (provider == Gateway.getLanProvider()) {
                for (String types : new String[] {
                    Request.INVITE, Request.BYE, Request.OPTIONS, Request.CANCEL, Request.ACK,
                    Request.REFER
                }) {
                    AllowHeader allowHeader = ProtocolObjects.headerFactory
                            .createAllowHeader(types);
                    response.addHeader(allowHeader);
                }
                contactHeader = SipUtilities.createContactHeader(null, provider, Gateway
                        .getSipxProxyTransport());

                SupportedHeader sh = ProtocolObjects.headerFactory
                        .createSupportedHeader("replaces");
                response.setHeader(sh);
            } else {

                for (String types : new String[] {
                    Request.INVITE, Request.BYE, Request.OPTIONS, Request.CANCEL, Request.ACK,
                    Request.OPTIONS
                }) {
                    AllowHeader allowHeader = ProtocolObjects.headerFactory
                            .createAllowHeader(types);
                    response.addHeader(allowHeader);
                }
                contactHeader = SipUtilities.createContactHeader(null, provider, null);
            }

            AcceptHeader acceptHeader = ProtocolObjects.headerFactory.createAcceptHeader(
                    "application", "sdp");
            response.setHeader(contactHeader);
            response.setHeader(acceptHeader);
            // Should probably have a configurable option for this.
            Locale locale = Locale.ENGLISH;
            AcceptLanguageHeader acceptLanguage = ProtocolObjects.headerFactory
                    .createAcceptLanguageHeader(locale);
            response.setHeader(acceptLanguage);
            Dialog dialog = requestEvent.getDialog();
            if (dialog != null) {
                // This is an In-dialog request.
                // We add our session description to the response.
                DialogApplicationData dat = DialogApplicationData.get(dialog);
                if (dat != null) {
                    BackToBackUserAgent b2bua = dat.getBackToBackUserAgent();
                    RtpSession sym = null;
                    if (provider == Gateway.getLanProvider()) {
                        sym = b2bua.getLanRtpSession(dialog);
                    } else {
                        sym = b2bua.getWanRtpSession(dialog);
                    }
                    SessionDescription sd = sym.getReceiver().getSessionDescription();
                    if (sd != null) {
                        response.setContent(sd.toString(), ProtocolObjects.headerFactory
                                .createContentTypeHeader("application", "sdp"));
                    }
                }

            }

            //
            // If In-Dialog, then the stack will create a server transaction for you to respond
            // stateufully. Hence that ST should be used to respond. If out of dialog, then
            // we simply respond statelessly ( no need to create a Server Transaction ).

            if (st == null) {
                provider.sendResponse(response);
            } else {
                st.sendResponse(response);
            }
        } catch (Exception ex) {
            logger.error("Internal error processing request ", ex);
            try {
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.SERVER_INTERNAL_ERROR, request);
                if (logger.isDebugEnabled()) {
                    response.setReasonPhrase(ex.getStackTrace()[0].getFileName() + ":"
                            + ex.getStackTrace()[0].getLineNumber());
                }

                if (st != null) {
                    st.sendResponse(response);
                } else {
                    provider.sendResponse(response);
                }

            } catch (Exception e) {
                throw new GatewayConfigurationException("Check gateway configuration", e);
            }
        }

    }

    /**
     * This is the response we get back from the Auto attendant.
     * 
     * @param requestEvent
     */
    private void processRefer(RequestEvent requestEvent) {
        TransactionApplicationData tad = null;
        try {

            logger.debug("Got a REFER - establishing new call leg and tearing down old call leg");
            Dialog dialog = requestEvent.getDialog();
            Request request = requestEvent.getRequest();
            SipProvider provider = (SipProvider) requestEvent.getSource();
            ServerTransaction stx = requestEvent.getServerTransaction();

            if (dialog == null || stx == null) {
                logger.error("Got an out of dialog REFER -- dropping");
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.NOT_IMPLEMENTED, request);
                response.setReasonPhrase("Can only handle In dialog REFER");
                if (stx != null)
                    stx.sendResponse(response);
                else
                    provider.sendResponse(response);
                return;
            }
            if (provider != Gateway.getLanProvider()) {
                // For now do not accept refer from the WAN side later we can
                // relax this restriction.
                Response response = ProtocolObjects.messageFactory.createResponse(
                        Response.NOT_IMPLEMENTED, request);
                response.setReasonPhrase("Can only handle REFER from LAN");
                if (stx != null)
                    stx.sendResponse(response);
                else
                    provider.sendResponse(response);
                return;
            }

            /*
             * Now Re-INVITE to the new location where REFER is pointing.
             */
            DialogApplicationData dat = (DialogApplicationData) dialog.getApplicationData();
            BackToBackUserAgent btobua = dat.getBackToBackUserAgent();

            Response response = ProtocolObjects.messageFactory.createResponse(Response.ACCEPTED,
                    request);
            ServerTransaction serverTransaction = requestEvent.getServerTransaction();
            serverTransaction.sendResponse(response);

            ContactHeader cth = SipUtilities.createContactHeader(null, provider, Gateway
                    .getSipxProxyTransport());
            response.setHeader(cth);

            /*
             * Stop the Music on hold.
             */

            if (Gateway.isReInviteSupported()) {
                // The ITSP supports re-invite. Send him a Re-INVITE
                // to determine what codec was negotiated.
                ReferInviteToSipxProxyContinuationData continuation = new ReferInviteToSipxProxyContinuationData(
                        request, dialog);

                btobua.querySdpFromPeerDialog(requestEvent, Operation.REFER_INVITE_TO_SIPX_PROXY,
                        continuation);
            } else {
                btobua.referInviteToSipxProxy(request, dialog, null);
            }

        } catch (ParseException ex) {
            // This should never happen
            logger.fatal("Internal error constructing message ", ex);
            throw new RuntimeException("Internal error", ex);

        } catch (InvalidArgumentException ex) {
            logger.fatal("Internal error -- invalid argument", ex);
            throw new RuntimeException("Internal error", ex);
        } catch (Exception ex) {
            logger.error("Unexpected exception while processing REFER", ex);
            if (tad != null) {
                ServerTransaction serverTransaction = tad.serverTransaction;
                sendBadRequestError(serverTransaction, ex);
            }
        }

    }

    /**
     * Processes an INCOMING ack from the PBX side.
     */
    private void processAck(RequestEvent requestEvent) {
        try {
            BackToBackUserAgent btobua = DialogApplicationData
                    .getBackToBackUserAgent(requestEvent.getDialog());

            if (btobua == null) {
                logger.debug("Could not find B2BUA -- not forwarding ACK ");
                return;
            }
            DialogApplicationData dad = (DialogApplicationData) requestEvent.getDialog()
                    .getApplicationData();

            Dialog dialog = dad.peerDialog;

            if (dialog == null) {
                logger.debug("Could not find peer dialog -- not forwarding ACK!");
                return;
            }

            /*
             * Forward the ACK if we have not already done so.
             */

            DialogApplicationData dat = (DialogApplicationData) dialog.getApplicationData();

            if (dat != null
                    && (!dat.isSdpAnswerPending)
                    && dialog.getState() == DialogState.CONFIRMED
                    && dat.lastResponse != null
                    && dat.lastResponse.getStatusCode() == Response.OK
                    && ((CSeqHeader) dat.lastResponse.getHeader(CSeqHeader.NAME)).getMethod()
                            .equals(Request.INVITE)) {
                logger.debug("createAck: " + dialog);
                Request ack = dialog.createAck(SipUtilities.getSeqNumber(dat.lastResponse));
                dialog.sendAck(ack);
                /*
                 * Setting this to null here handles the case of Re-invitations.
                 */
                dat.lastResponse = null;
                dat.lastAckSent = System.currentTimeMillis();

            }

        } catch (Exception ex) {
            logger.error("Problem sending ack ", ex);

        }

    }

    /**
     * Processes an INCOMING CANCEL from the PBX side.
     */
    private void processCancel(RequestEvent requestEvent) {

        Dialog dialog = requestEvent.getDialog();
        BackToBackUserAgent btobua = DialogApplicationData.getBackToBackUserAgent(requestEvent
                .getDialog());

        try {
            Response cancelOk = SipUtilities.createResponse(requestEvent.getServerTransaction(),
                    Response.OK);
            requestEvent.getServerTransaction().sendResponse(cancelOk);
            ServerTransaction inviteServerTransaction = ((SIPServerTransaction) requestEvent
                    .getServerTransaction()).getCanceledInviteTransaction();

            if (inviteServerTransaction.getState() == TransactionState.PROCEEDING) {
                Response response = SipUtilities.createResponse(inviteServerTransaction,
                        Response.REQUEST_TERMINATED);
                inviteServerTransaction.sendResponse(response);
            } else {
                // Too late to cancel.
                return;
            }
            TransactionApplicationData tad = (TransactionApplicationData) inviteServerTransaction
                    .getApplicationData();
            ClientTransaction ct = tad.clientTransaction;
            ItspAccountInfo itspAccount = btobua.getItspAccountInfo();
            String transport = itspAccount != null ? itspAccount.getOutboundTransport()
                    : Gateway.DEFAULT_ITSP_TRANSPORT;
            if (ct.getState() == TransactionState.CALLING
                    || ct.getState() == TransactionState.PROCEEDING) {
                Request cancelRequest = ct.createCancel();

                SipProvider provider = SipUtilities.getPeerProvider((SipProvider) requestEvent
                        .getSource(), transport);
                ClientTransaction clientTransaction = provider
                        .getNewClientTransaction(cancelRequest);
                clientTransaction.sendRequest();
            } else {
                logger.debug("CallControlManager:processCancel -- sending BYE " + ct.getState());
                DialogApplicationData dialogApplicationData = (DialogApplicationData) dialog
                        .getApplicationData();

                Dialog peerDialog = dialogApplicationData.peerDialog;
                if (peerDialog != null) {
                    Request byeRequest = peerDialog.createRequest(Request.BYE);
                    SipProvider provider = SipUtilities.getPeerProvider(
                            (SipProvider) requestEvent.getSource(), transport);
                    ClientTransaction byeCt = provider.getNewClientTransaction(byeRequest);
                    peerDialog.sendRequest(byeCt);
                }
            }
        } catch (Exception ex) {
            logger.error("Unexpected exception processing cancel", ex);
        }

    }

    /**
     * Processes an INCOMING BYE
     */
    private void processBye(RequestEvent requestEvent) {
        try {
            BackToBackUserAgent b2bua = DialogApplicationData.getBackToBackUserAgent(requestEvent
                    .getDialog());

            if (b2bua != null) {

                b2bua.processBye(requestEvent);

            } else {
                // Respond with an error
                Response response = SipUtilities.createResponse(requestEvent
                        .getServerTransaction(), Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST);
                requestEvent.getServerTransaction().sendResponse(response);
            }

        } catch (Exception ex) {
            logger.error("Problem sending bye", ex);
        }

    }

    /**
     * Sends an SDP answer to the peer of this dialog.
     * 
     * @param response
     * @param dialog
     * @throws Exception
     */
    private void sendSdpAnswerInAck(Response response, Dialog dialog) throws Exception {
        // logger.debug("sendSdpAnswerInAck " + response);
        DialogApplicationData dat = (DialogApplicationData) dialog.getApplicationData();
        BackToBackUserAgent b2bua = dat.getBackToBackUserAgent();
        Dialog peerDialog = DialogApplicationData.getPeerDialog(dialog);
        DialogApplicationData peerDialogApplicationData = (DialogApplicationData) peerDialog
                .getApplicationData();
        if (logger.isDebugEnabled()) {
            logger.debug("sendSdpAnswerInAck : peerDialog = " + peerDialog
                    + " peerDialogApplicationData = " + peerDialogApplicationData
                    + "\nlastResponse = " + peerDialogApplicationData.lastResponse);
        }

        peerDialogApplicationData.isSdpAnswerPending = false;
        if (response.getContentLength().getContentLength() != 0) {

            SessionDescription sd = SipUtilities.cleanSessionDescription(SipUtilities
                    .getSessionDescription(response), Gateway.getCodecName());

            /*
             * Got a Response to our SDP query. Shuffle to the other end.
             */
            Request ackRequest = peerDialog.createAck(SipUtilities
                    .getSeqNumber(peerDialogApplicationData.lastResponse));

            if (((DialogExt) dialog).getSipProvider() == Gateway.getLanProvider()) {
                // We did a SDP query. So we need to put an SDP
                // Answer in the response.

                b2bua.getWanRtpSession(peerDialog).getReceiver().setSessionDescription(sd);

                SipUtilities.incrementSessionVersion(sd);

            } else {
                b2bua.getLanRtpSession(peerDialog).getReceiver().setSessionDescription(sd);

                SipUtilities.incrementSessionVersion(sd);
            }

            if (Gateway.isReInviteSupported()) {
                ackRequest.setContent(sd.toString(), ProtocolObjects.headerFactory
                        .createContentTypeHeader("application", "sdp"));
                peerDialog.sendAck(ackRequest);
            }

        } else {
            logger.error("ERROR  0 contentLength ");
        }
    }

    /**
     * Process an INVITE response.
     * 
     * @param responseEvent -- the response event.
     */
    private void processInviteResponse(ResponseEvent responseEvent) {

        ServerTransaction serverTransaction = null;
        BackToBackUserAgent b2bua = null;

        SipProvider provider = (SipProvider) responseEvent.getSource();
        Response response = responseEvent.getResponse();
        logger.debug("processInviteResponse : " + ((SIPResponse) response).getFirstLine());

        Dialog dialog = responseEvent.getDialog();

        try {

            if (responseEvent.getClientTransaction() == null) {
                logger.debug("Could not find client transaction -- must be stray response.");
                if (response.getStatusCode() == 200 && dialog != null) {
                    Request ack = dialog.createAck(((CSeqHeader) response
                            .getHeader(CSeqHeader.NAME)).getSeqNumber());
                    dialog.sendAck(ack);

                }
                return;
            } else if (((TransactionApplicationData) responseEvent.getClientTransaction()
                    .getApplicationData()).operation == Operation.SESSION_TIMER
                    && dialog != null) {
                if (response.getStatusCode() == 200) {
                    Request ack = dialog.createAck(((CSeqHeader) response
                            .getHeader(CSeqHeader.NAME)).getSeqNumber());
                    dialog.sendAck(ack);

                } else {
                    b2bua = DialogApplicationData.get(dialog).getBackToBackUserAgent();
                    b2bua.tearDown();
                }
                return;

            }
        } catch (Exception ex) {
            logger.error("Unexpected error sending ACK for 200 OK", ex);
            return;
        }

        DialogApplicationData dat = (DialogApplicationData) dialog.getApplicationData();
        if (dat == null) {
            logger.error("Could not find DialogApplicationData -- dropping the response");
            try {
                if (response.getStatusCode() == 200) {
                    Request ack = dialog.createAck(((CSeqHeader) response
                            .getHeader(CSeqHeader.NAME)).getSeqNumber());
                    dialog.sendAck(ack);

                }

                return;
            } catch (Exception ex) {
                logger.error("Unexpected error sending ACK for 200 OK");
                return;
            }

        }

        b2bua = dat.getBackToBackUserAgent();
        if (b2bua == null) {
            logger.fatal("Could not find a BackToBackUA -- dropping the response");
            throw new RuntimeException("Could not find a B2BUA for this response : " + response);
        }

        try {

            if (response.getStatusCode() > 100 && response.getStatusCode() <= 200) {

                /*
                 * Set our final dialog. Note that the 1xx Dialog may be different.
                 */
                b2bua.addDialog(dialog);

                /*
                 * We store away our outgoing sdp offer in the application data of the client tx.
                 */
                TransactionApplicationData tad = (TransactionApplicationData) responseEvent
                        .getClientTransaction().getApplicationData();

                logger.debug("Operation = " + tad.operation);

                /*
                 * The TransactionApplicationData operator will indicate what the OK is for.
                 */
                if (tad.operation == Operation.QUERY_SDP_FROM_PEER_DIALOG
                        && response.getStatusCode() == 200) {
                    Operation operation = tad.continuationOperation;
                    logger.debug("continuationOperation = " + operation);

                    /*
                     * Send him a PRACK at this point??
                     */
                    
                  

                    if (response.getContentLength().getContentLength() == 0) {
                        logger.error("DROPPING CALL -- Expecting a content length != 0 ");
                        b2bua.tearDown();
                        return;
                    }
                    SessionDescription sd = SipUtilities.getSessionDescription(response);

                    dat.lastResponse = response;
                    if ( operation == null ) {
                        ServerTransaction st = tad.serverTransaction;
                        Request serverRequest = st.getRequest();
                        Response newResponse = ProtocolObjects.messageFactory.createResponse(Response.OK, serverRequest);
                        Dialog peerDialog = dat.peerDialog;
                        RtpSession wanRtpSession = b2bua.getWanRtpSession(peerDialog);
                        wanRtpSession.getReceiver().setSessionDescription(sd);
                        DialogApplicationData peerDat = DialogApplicationData.get(peerDialog);
                        ContactHeader contactHeader = (ContactHeader) peerDat.request.getHeader(ContactHeader.NAME);
                        ContentTypeHeader cth = ProtocolObjects.headerFactory.createContentTypeHeader("application", "sdp");
                        newResponse.setContent(sd.toString(), cth  );
                        newResponse.setHeader(contactHeader);
                        dat.isSdpAnswerPending = true;
                        st.sendResponse(newResponse);
                    } else if (operation == Operation.REFER_INVITE_TO_SIPX_PROXY) {

                        ReferInviteToSipxProxyContinuationData continuation = (ReferInviteToSipxProxyContinuationData) tad.continuationData;
                        b2bua.getLanRtpSession(continuation.dialog).getReceiver().setSdpQueried(
                                true);
                        b2bua.getLanRtpSession(continuation.dialog).getReceiver()
                                .setSessionDescription(sd);
                        b2bua.referInviteToSipxProxy(continuation.request, continuation.dialog,
                                sd);
                    } else if (operation == Operation.SEND_INVITE_TO_MOH_SERVER) {

                        ReInviteProcessingContinuationData continuation = (ReInviteProcessingContinuationData) tad.continuationData;
                        b2bua.getLanRtpSession(continuation.dialog).getReceiver()
                                .setSessionDescription(sd);
                        b2bua.getLanRtpSession(continuation.dialog).getReceiver().setSdpQueried(
                                true);
                        this.adjustSessionParameters(continuation.serverTransaction,
                                continuation.request, continuation.dialog, continuation.provider,
                                dialog);

                    }

                } else if (tad.operation == Operation.SEND_INVITE_TO_ITSP
                        || tad.operation == Operation.SEND_INVITE_TO_SIPX_PROXY) {

                    /*
                     * Store away our incoming response - get ready for ACKL
                     */
                    dat.lastResponse = response;
                    dat.setBackToBackUserAgent(b2bua);

                    dialog.setApplicationData(dat);

                    /*
                     * Now send the respose to the server side of the transaction.
                     */
                    serverTransaction = tad.serverTransaction;
                    Response newResponse = ProtocolObjects.messageFactory.createResponse(response
                            .getStatusCode(), serverTransaction.getRequest());
                    SupportedHeader sh = ProtocolObjects.headerFactory
                            .createSupportedHeader("replaces");

                    newResponse.setHeader(sh);

                    String toTag = tad.toTag;
                    if (toTag == null) {
                        toTag = Integer.toString(Math.abs(new Random().nextInt()));
                        tad.toTag = toTag;
                    }

                    ToHeader toHeader = (ToHeader) tad.serverTransaction.getRequest().getHeader(
                            ToHeader.NAME);

                    String user = ((SipURI) toHeader.getAddress().getURI()).getUser();
                    ContactHeader contactHeader = null;

                    /*
                     * Set the contact address for the OK. Note that ITSP may want global
                     * addressing.
                     */
                    if (tad.operation == Operation.SEND_INVITE_TO_ITSP) {
                        contactHeader = SipUtilities.createContactHeader(user,
                                tad.serverTransactionProvider, Gateway.getSipxProxyTransport());
                    } else {
                        contactHeader = SipUtilities.createContactHeader(
                                tad.serverTransactionProvider, tad.itspAccountInfo);
                    }

                    newResponse.setHeader(contactHeader);
                    ToHeader newToHeader = (ToHeader) newResponse.getHeader(ToHeader.NAME);
                    newToHeader.setTag(toTag);

                    /*
                     * Fix up the media session using the port in the incoming sdp answer.
                     */
                    ContentTypeHeader cth = (ContentTypeHeader) response
                            .getHeader(ContentTypeHeader.NAME);

                    SessionDescription newSd = null;
                    if (response.getRawContent() != null
                            && cth.getContentType().equals("application")
                            && cth.getContentSubType().equals("sdp")) {
                        /*
                         * The incoming media session.
                         */
                        SessionDescription sessionDescription = SdpFactory.getInstance()
                                .createSessionDescription(new String(response.getRawContent()));
                        if (logger.isDebugEnabled()) {
                            logger.debug("SessionDescription = "
                                    + new String(response.getRawContent()));
                        }

                        DialogApplicationData dialogApplicationData = (DialogApplicationData) dialog
                                .getApplicationData();

                        RtpSession rtpSession = dialogApplicationData.getRtpSession();
                        RtpTransmitterEndpoint hisEndpoint = null;
                        if (rtpSession != null) {
                            hisEndpoint = rtpSession.getTransmitter();
                        }

                        if (hisEndpoint == null) {
                            hisEndpoint = new RtpTransmitterEndpoint(rtpSession, b2bua
                                    .getSymmitronClient());

                        }

                        tad.outgoingSession.setTransmitter(hisEndpoint);

                        hisEndpoint.setSessionDescription(sessionDescription);
                        int keepaliveInterval;
                        KeepaliveMethod keepaliveMethod;

                        if (tad.operation == Operation.SEND_INVITE_TO_ITSP) {
                            keepaliveInterval = Gateway.getMediaKeepaliveMilisec();
                            keepaliveMethod = tad.itspAccountInfo.getRtpKeepaliveMethod();

                        } else {
                            keepaliveInterval = 0;
                            keepaliveMethod = KeepaliveMethod.NONE;
                        }
                        hisEndpoint.setIpAddressAndPort(keepaliveInterval, keepaliveMethod);

                        RtpReceiverEndpoint incomingEndpoint = tad.incomingSession.getReceiver();
                        newSd = SdpFactory.getInstance().createSessionDescription(
                                new String(response.getRawContent()));
                        incomingEndpoint.setSessionDescription(newSd);
                        newResponse.setContent(newSd.toString(), cth);

                        tad.backToBackUa.getRtpBridge().start();

                    }

                    serverTransaction.sendResponse(newResponse);
                } else if (tad.operation == Operation.REFER_INVITE_TO_SIPX_PROXY
                        || tad.operation == Operation.SPIRAL_BLIND_TRANSFER_INVITE_TO_ITSP) {

                    /*
                     * This is the case of Refer redirection. In this case, we have already
                     * established a call leg with the Auto attendant. We already have a RTP
                     * session established with the Auto attendant. We need to redirect the
                     * outbound RTP stream. Fix up the media session using the port in the
                     * incoming sdp answer.
                     */
                    ContentTypeHeader cth = (ContentTypeHeader) response
                            .getHeader(ContentTypeHeader.NAME);
                    Dialog referDialog = tad.referingDialog;

                    if (response.getRawContent() != null
                            && cth.getContentType().equals("application")
                            && cth.getContentSubType().equals("sdp")) {
                        /*
                         * The incoming media session.
                         */
                        SessionDescription sessionDescription = SdpFactory.getInstance()
                                .createSessionDescription(new String(response.getRawContent()));
                        logger.debug("Processing ReferRedirection Response");

                        RtpSession rtpSession = ((DialogApplicationData) referDialog
                                .getApplicationData()).getRtpSession();

                        if (rtpSession != null) {

                            /*
                             * Note that we are just pointing the transmitter to another location.
                             * The receiver stays as is.
                             */
                            rtpSession.getTransmitter().setSessionDescription(sessionDescription);

                            /*
                             * Grab the RTP session previously pointed at by the REFER dialog.
                             */
                            b2bua.getRtpBridge().addSym(rtpSession);

                            ((DialogApplicationData) dialog.getApplicationData())
                                    .setRtpSession(rtpSession);

                        } else {
                            logger
                                    .debug("Processing ReferRedirection: Could not find RtpSession for referred dialog");
                        }

                    }

                    /*
                     * Got an OK for the INVITE ( that means that somebody picked up ) so we can
                     * hang up the call. We have already redirected the RTP media to the
                     * redirected party at this point.
                     */

                    if (referDialog.getState() != DialogState.TERMINATED) {
                        Request notifyRequest = referDialog.createRequest(Request.NOTIFY);
                        EventHeader eventHeader = ProtocolObjects.headerFactory
                                .createEventHeader("refer");
                        notifyRequest.addHeader(eventHeader);
                        SubscriptionStateHeader subscriptionStateHeader = ProtocolObjects.headerFactory
                                .createSubscriptionStateHeader("active");
                        notifyRequest.addHeader(subscriptionStateHeader);
                        // Content-Type: message/sipfrag;version=2.0
                        ContentTypeHeader contentTypeHeader = ProtocolObjects.headerFactory
                                .createContentTypeHeader("message", "sipfrag");
                        // contentTypeHeader.setParameter("version", "2.0");
                        String content = ((SIPResponse) response).getStatusLine().toString();
                        notifyRequest.setContent(content, contentTypeHeader);
                        SipProvider referProvider = ((SIPDialog) referDialog).getSipProvider();
                        ClientTransaction ctx = referProvider
                                .getNewClientTransaction(notifyRequest);
                        referDialog.sendRequest(ctx);

                    }

                    if (response.getContentLength().getContentLength() != 0) {
                        Dialog peerDialog = DialogApplicationData.getPeerDialog(dialog);
                        if (DialogApplicationData.get(peerDialog).isSdpAnswerPending) {
                            this.sendSdpAnswerInAck(response, dialog);
                        } else {
                            logger.debug("Not sending SdpAnswer");
                        }
                    }

                    /*
                     * We directly send ACK.
                     */
                    if (response.getStatusCode() == Response.OK) {

                        b2bua.addDialog(dialog);
                        // Thread.sleep(100);
                        Request ackRequest = dialog.createAck(((CSeqHeader) response
                                .getHeader(CSeqHeader.NAME)).getSeqNumber());
                        dialog.sendAck(ackRequest);

                    }
                    /*
                     * If there is a Music on hold dialog -- tear it down
                     */

                    if (response.getStatusCode() == Response.OK && dat.musicOnHoldDialog != null
                            && dat.musicOnHoldDialog.getState() != DialogState.TERMINATED) {

                        if (!Gateway.isReInviteSupported()) {
                            b2bua.sendByeToMohServer(dat.musicOnHoldDialog);
                        } else {

                            Dialog peerDialog = DialogApplicationData.getPeerDialog(dialog);
                            DialogApplicationData peerDat = DialogApplicationData.get(peerDialog);
                            peerDat.getRtpSession().removeHold(null, peerDialog);
                        }

                    }

                } else if (tad.operation.equals(Operation.SEND_INVITE_TO_MOH_SERVER)) {
                    if (response.getStatusCode() == Response.OK) {
                        Request ack = dialog.createAck(((CSeqHeader) response
                                .getHeader(CSeqHeader.NAME)).getSeqNumber());

                        dialog.sendAck(ack);
                        Dialog peerDialog = DialogApplicationData.getPeerDialog(dialog);
                        if (Gateway.isReInviteSupported()
                                && response.getContentLength().getContentLength() != 0
                                && peerDialog != null) {
                            this.sendSdpAnswerInAck(response, dialog);
                        }

                    }
                } else if (tad.operation.equals(Operation.FORWARD_REINVITE)) {

                    /*
                     * Store away our incoming response - get ready for ACKL
                     */
                    dat.lastResponse = response;
                    dat.setBackToBackUserAgent(b2bua);

                    dialog.setApplicationData(dat);

                    /*
                     * Now send the respose to the server side of the transaction.
                     */
                    serverTransaction = tad.serverTransaction;

                    /*
                     * If we have a server transaction associated with the response, we ack when
                     * the other side acks.
                     */
                    if (serverTransaction != null) {
                        Response newResponse = ProtocolObjects.messageFactory.createResponse(
                                response.getStatusCode(), serverTransaction.getRequest());
                        SupportedHeader sh = ProtocolObjects.headerFactory
                                .createSupportedHeader("replaces");

                        newResponse.setHeader(sh);
                        ContactHeader contactHeader = SipUtilities.createContactHeader(null,
                                tad.serverTransactionProvider, Gateway.getSipxProxyTransport());
                        newResponse.setHeader(contactHeader);
                        SessionDescription sd = SipUtilities.getSessionDescription(response);
                        ContentTypeHeader cth = ProtocolObjects.headerFactory
                                .createContentTypeHeader("application", "sdp");
                        Dialog peerDialog = DialogApplicationData.getPeerDialog(dialog);
                        SipProvider peerProvider = ((DialogExt)peerDialog).getSipProvider();
                        RtpSession rtpSession = b2bua.getLanRtpSession(peerDialog);
                        rtpSession.getReceiver().setSessionDescription(sd);
                        newResponse.setContent(sd.toString(), cth);
                        if ( peerProvider != Gateway.getLanProvider()) {
                            DialogApplicationData peerDat = DialogApplicationData.get(peerDialog);
                            if ( peerDat.itspInfo == null || peerDat.itspInfo.isGlobalAddressingUsed()) {
                                SipUtilities.setGlobalAddress(newResponse);
                            }
                        }
                      
                        serverTransaction.sendResponse(newResponse);
                    } else {
                        Request ack = dialog.createAck(SipUtilities.getSeqNumber(response));
                        dialog.sendAck(ack);
                    }

                } else if (tad.operation == Operation.HANDLE_SPIRAL_INVITE_WITH_REPLACES) {

                    Request ack = dialog.createAck(((CSeqHeader) response
                            .getHeader(CSeqHeader.NAME)).getSeqNumber());

                    dialog.sendAck(ack);
                    this.sendSdpAnswerInAck(response, dialog);
                } else {
                    logger.fatal("CallControlManager: Unknown Case in if statement ");
                }
            } else if (response.getStatusCode() > 200) {
                if (responseEvent.getClientTransaction() == null) {
                    logger.warn("null client transaction");
                    return;
                }

                logger.debug("Processing ERROR Response " + response.getStatusCode());

                // Processing an error resonse.
                ClientTransaction ct = responseEvent.getClientTransaction();
                TransactionApplicationData tad = (TransactionApplicationData) ct
                        .getApplicationData();
                if (tad != null) {
                    serverTransaction = tad.serverTransaction;
                    /*
                     * If we previously referred an Invite to the sipx proxy and the proxy did not
                     * know what to do with then we just do nothing.
                     */
                    if (tad.operation != Operation.REFER_INVITE_TO_SIPX_PROXY) {

                        if (serverTransaction != null) {
                            Request originalRequest = serverTransaction.getRequest();
                            // tad.backToBackUa.getRtpBridge().stop();
                            // this.callTable.remove(callid);
                            Response newResponse = ProtocolObjects.messageFactory.createResponse(
                                    response.getStatusCode(), originalRequest);
                            SupportedHeader sh = ProtocolObjects.headerFactory
                                    .createSupportedHeader("replaces");
                            newResponse.setHeader(sh);
                            serverTransaction.sendResponse(newResponse);
                        } else {
                            b2bua.tearDown();
                        }
                    }
                }
            }

        } catch (ParseException ex) {
            logger.error("Unexpected parse exception", ex);
            throw new RuntimeException("Unexpected exception", ex);
        } catch (InvalidArgumentException ex) {
            logger.error("Unpexpected exception", ex);
            throw new RuntimeException("Unexpected exception", ex);
        } catch (Exception ex) {
            // Some other exception occured during processing of the request.
            logger.error("Exception while processing inbound response ", ex);

            if (serverTransaction != null) {
                sendBadRequestError(serverTransaction, ex);
            }
            if (b2bua != null) {
                try {
                    b2bua.tearDown();
                } catch (Exception e) {
                    logger.error("unexpected exception", e);
                }
            }
        }

    }

    private void processCancelResponse(ResponseEvent responseEvent) {
        logger.debug("processCancelResponse");

    }

    private void processNotifyResponse(ResponseEvent responseEvent) {
        logger.debug("CallControlManager: processNotifyResponse");
    }

    private void processByeResponse(ResponseEvent responseEvent) {
        try {
            logger.debug("CallControlManager: processByeResponse");
            ClientTransaction ct = responseEvent.getClientTransaction();
            TransactionApplicationData tad = (TransactionApplicationData) ct.getApplicationData();
            if (tad != null) {
                ServerTransaction st = tad.serverTransaction;
                if (st != null) {
                    Response response = responseEvent.getResponse();
                    Response newResponse = SipUtilities.createResponse(st, response
                            .getStatusCode());
                    st.sendResponse(newResponse);
                }
            }

            // Send Re-INVITE to the ITSP.
            if (false && tad.operation == Operation.SEND_BYE_TO_MOH_SERVER
                    && Gateway.isReInviteSupported()) {
                Dialog dialog = responseEvent.getDialog();
                DialogApplicationData dat = DialogApplicationData.get(dialog);
                Dialog peerDialog = dat.peerDialog;
                if (peerDialog.getState() != DialogState.TERMINATED) {
                    DialogApplicationData peerDat = DialogApplicationData.get(peerDialog);
                    RtpSession rtpSession = peerDat.getRtpSession();
                    rtpSession.forwardReInvite(null, dialog);
                } else {
                    logger.debug("peerDialog is TERMINATED " + peerDialog);
                }
            }
        } catch (Exception ex) {
            logger.error("Exception forwarding bye response", ex);
        }
    }

    /**
     * Get a new B2bua for a given call Id.
     * 
     * @param callId -- callId for which we want our user agent.
     */

    synchronized BackToBackUserAgent getBackToBackUserAgent(SipProvider provider,
            Request request, Dialog dialog, boolean callOriginatedFromLan) throws Exception {

        String callId = SipUtilities.getCallId(request);
        // BackToBackUserAgent b2bua = callTable.get(callId);
        BackToBackUserAgent b2bua = null;
        try {

            ItspAccountInfo accountInfo = null;
            if (callOriginatedFromLan) {
                accountInfo = Gateway.getAccountManager().getAccount(
                        (SipURI) request.getRequestURI());
            } else {
                /*
                 * Check the Via header of the inbound request to see if this is an account we
                 * know about. This will be the case when there is a registration for the request
                 * and we have a hop to its proxy. If we know where the request is coming from, we
                 * can set up various response fields accordingly.
                 */
                ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
                String host = viaHeader.getHost();
                int port = viaHeader.getPort();
                accountInfo = Gateway.getAccountManager().getItspAccount(host, port);
            }

            if (this.backToBackUserAgentTable.containsKey(callId)) {
                b2bua = this.backToBackUserAgentTable.get(callId);
            } else {

                b2bua = new BackToBackUserAgent(provider, request, dialog, accountInfo);

                DialogApplicationData.attach(b2bua, dialog, request);

                this.backToBackUserAgentTable.put(callId, b2bua);
            }

        } catch (IOException ex) {
            logger.error("unepxected exception", ex);
            throw new RuntimeException("IOException -- check symmitron", ex);
        } catch (Exception ex) {
            logger.error("unexpected exception ", ex);
            throw new RuntimeException("Unepxected exception cloning");
        }
        return b2bua;

    }

    /**
     * Process an incoming request.
     */
    void processRequest(RequestEvent requestEvent) {
        Request request = requestEvent.getRequest();
        String method = request.getMethod();
        if (method.equals(Request.INVITE)) {
            processInvite(requestEvent);
        } else if (method.equals(Request.ACK)) {
            processAck(requestEvent);
        } else if (method.equals(Request.BYE)) {
            processBye(requestEvent);
        } else if (method.equals(Request.CANCEL)) {
            processCancel(requestEvent);
        } else if (method.equals(Request.OPTIONS)) {
            processOptions(requestEvent);
        } else if (method.equals(Request.REFER)) {
            processRefer(requestEvent);
        }

    }

    /**
     * Process an incoming response
     */
    void processResponse(ResponseEvent responseEvent) {
        Response response = responseEvent.getResponse();
        String method = ((CSeqHeader) response.getHeader(CSeqHeader.NAME)).getMethod();
        if (method.equals(Request.INVITE)) {
            processInviteResponse(responseEvent);
        } else if (method.equals(Request.CANCEL)) {
            processCancelResponse(responseEvent);
        } else if (method.equals(Request.BYE)) {
            processByeResponse(responseEvent);
        } else if (method.equals(Request.NOTIFY)) {
            processNotifyResponse(responseEvent);
        }
    }

    void stop() {
        /*
         * The following code assumes that the sip stack is being used just for this service.
         */
        for (Dialog dialog : ((SipStackImpl) ProtocolObjects.sipStack).getDialogs()) {
            dialog.delete();
        }

    }

    /**
     * Remove all the records in the back to back user agent table corresponsing to a given B2BUA.
     * 
     * @param backToBackUserAgent
     */
    void removeBackToBackUserAgent(BackToBackUserAgent backToBackUserAgent) {
        for (Iterator<String> keyIterator = this.backToBackUserAgentTable.keySet().iterator(); keyIterator
                .hasNext();) {
            String key = keyIterator.next();
            if (this.backToBackUserAgentTable.get(key) == backToBackUserAgent) {
                keyIterator.remove();
            }
        }

        logger.debug("CallControlManager: removeBackToBackUserAgent() after removal "
                + this.backToBackUserAgentTable);

    }

    /**
     * Dump the B2BUA table for memory debugging.
     */

    void dumpBackToBackUATable() {

        logger.debug("B2BUATable = " + this.backToBackUserAgentTable);

    }

    /**
     * Get the Back to back user agent set.
     */
    Collection<BackToBackUserAgent> getBackToBackUserAgents() {
        return this.backToBackUserAgentTable.values();
    }

    /**
     * Get the B2BUA for a given callId. This method is used by the XML RPC interface to cancel a
     * call hence needs to be public.
     * 
     * @param callId
     * @return
     */
    public BackToBackUserAgent getBackToBackUserAgent(String callId) {

        return this.backToBackUserAgentTable.get(callId);

    }

}
