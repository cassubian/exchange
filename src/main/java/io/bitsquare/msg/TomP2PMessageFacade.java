/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.msg;

import io.bitsquare.arbitrator.Arbitrator;
import io.bitsquare.msg.listeners.AddOfferListener;
import io.bitsquare.msg.listeners.ArbitratorListener;
import io.bitsquare.msg.listeners.BootstrapListener;
import io.bitsquare.msg.listeners.GetPeerAddressListener;
import io.bitsquare.msg.listeners.IncomingMessageListener;
import io.bitsquare.msg.listeners.OfferBookListener;
import io.bitsquare.msg.listeners.OutgoingMessageListener;
import io.bitsquare.network.Peer;
import io.bitsquare.network.tomp2p.TomP2PPeer;
import io.bitsquare.offer.Offer;
import io.bitsquare.user.User;

import java.io.IOException;

import java.security.PublicKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import javafx.application.Platform;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;

import net.tomp2p.dht.FutureGet;
import net.tomp2p.dht.FuturePut;
import net.tomp2p.dht.FutureRemove;
import net.tomp2p.futures.BaseFuture;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.BaseFutureListener;
import net.tomp2p.futures.FutureDirect;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number640;
import net.tomp2p.storage.Data;
import net.tomp2p.utils.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * That facade delivers direct messaging and DHT functionality from the TomP2P library
 * It is the translating domain specific functionality to the messaging layer.
 * The TomP2P library codebase shall not be used outside that facade.
 * That way we limit the dependency of the TomP2P library only to that class (and it's sub components).
 * <p>
 * TODO: improve callbacks that Platform.runLater is not necessary. We call usually that methods form teh UI thread.
 */
class TomP2PMessageFacade implements MessageFacade {
    private static final Logger log = LoggerFactory.getLogger(TomP2PMessageFacade.class);
    private static final String ARBITRATORS_ROOT = "ArbitratorsRoot";

    private final P2PNode p2pNode;
    private final User user;

    private final List<OfferBookListener> offerBookListeners = new ArrayList<>();
    private final List<ArbitratorListener> arbitratorListeners = new ArrayList<>();
    private final List<IncomingMessageListener> incomingMessageListeners = new ArrayList<>();
    private final LongProperty invalidationTimestamp = new SimpleLongProperty(0);


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TomP2PMessageFacade(User user, P2PNode p2pNode) {
        this.user = user;
        this.p2pNode = p2pNode;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public Methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void init(int port, BootstrapListener bootstrapListener) {
        p2pNode.setMessageBroker(this);
        p2pNode.setKeyPair(user.getMessageKeyPair());

        p2pNode.start(port, bootstrapListener);
    }

    public void shutDown() {
        if (p2pNode != null)
            p2pNode.shutDown();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Find peer address by publicKey
    ///////////////////////////////////////////////////////////////////////////////////////////


    public void getPeerAddress(PublicKey publicKey, GetPeerAddressListener listener) {
        final Number160 locationKey = Utils.makeSHAHash(publicKey.getEncoded());
        FutureGet futureGet = p2pNode.getDomainProtectedData(locationKey, publicKey);

        futureGet.addListener(new BaseFutureAdapter<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture baseFuture) throws Exception {
                if (isSuccess(baseFuture) && futureGet.data() != null) {
                    final Peer peer = (Peer) futureGet.data().object();
                    Platform.runLater(() -> listener.onResult(peer));
                }
                else {
                    Platform.runLater(listener::onFailed);
                }
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addOffer(Offer offer, AddOfferListener addOfferListener) {
        Number160 locationKey = Number160.createHash(offer.getCurrency().getCurrencyCode());
        try {
            final Data offerData = new Data(offer);

            // the offer is default 30 days valid
            int defaultOfferTTL = 30 * 24 * 60 * 60;
            offerData.ttlSeconds(defaultOfferTTL);
            log.trace("Add offer to DHT requested. Added data: [locationKey: " + locationKey +
                    ", hash: " + offerData.hash().toString() + "]");
            FuturePut futurePut = p2pNode.addProtectedData(locationKey, offerData);
            futurePut.addListener(new BaseFutureListener<BaseFuture>() {
                                      @Override
                                      public void operationComplete(BaseFuture future) throws Exception {
                                          if (isSuccess(future)) {
                                              Platform.runLater(() -> {
                                                  addOfferListener.onComplete();
                                                  offerBookListeners.stream().forEach(listener -> {
                                                      try {
                                                          Object offerDataObject = offerData.object();
                                                          if (offerDataObject instanceof Offer) {
                                                              log.error("Added offer to DHT with ID: " + ((Offer)
                                                                      offerDataObject).getId());
                                                              listener.onOfferAdded((Offer) offerDataObject);
                                                          }
                                                      } catch (ClassNotFoundException | IOException e) {
                                                          e.printStackTrace();
                                                          log.error("Add offer to DHT failed: " + e.getMessage());
                                                      }
                                                  });

                                                  // TODO will be removed when we don't use polling anymore
                                                  writeInvalidationTimestampToDHT(locationKey);
                                                  log.trace("Add offer to DHT was successful. Added data: " +
                                                          "[locationKey: " + locationKey +
                                                          ", value: " + offerData + "]");
                                              });
                                          }
                                          else {
                                              Platform.runLater(() -> {
                                                  addOfferListener.onFailed("Add offer to DHT failed.",
                                                          new Exception("Add offer to DHT failed. Reason: " + future
                                                                  .failedReason()));
                                                  log.error("Add offer to DHT failed. Reason: " + future.failedReason
                                                          ());
                                              });
                                          }
                                      }

                                      @Override
                                      public void exceptionCaught(Throwable t) throws Exception {
                                          Platform.runLater(() -> {
                                              addOfferListener.onFailed("Add offer to DHT failed with an exception.",
                                                      t);
                                              log.error("Add offer to DHT failed with an exception: " + t.getMessage());
                                          });
                                      }
                                  }

            );
        } catch (IOException e) {
            Platform.runLater(() -> {
                addOfferListener.onFailed("Add offer to DHT failed with an exception.", e);
                log.error("Add offer to DHT failed with an exception: " + e.getMessage());
            });
        }
    }

    //TODO remove is failing, probably due Coin or Fiat class (was working before)
    // objects are identical but returned object form network might have some problem with serialisation?

    public void removeOffer(Offer offer) {
        Number160 locationKey = Number160.createHash(offer.getCurrency().getCurrencyCode());
        try {
            final Data offerData = new Data(offer);
            log.trace("Remove offer from DHT requested. Removed data: [locationKey: " + locationKey +
                    ", hash: " + offerData.hash().toString() + "]");
            FutureRemove futureRemove = p2pNode.removeFromDataMap(locationKey, offerData);
            futureRemove.addListener(new BaseFutureListener<BaseFuture>() {
                @Override
                public void operationComplete(BaseFuture future) throws Exception {
                    if (isSuccess(future)) {
                        Platform.runLater(() -> {
                            offerBookListeners.stream().forEach(offerBookListener -> {
                                try {
                                    Object offerDataObject = offerData.object();
                                    if (offerDataObject instanceof Offer) {
                                        log.trace("Remove offer from DHT was successful. Removed data: [key: " +
                                                locationKey + ", " +
                                                "offer: " + offerDataObject + "]");
                                        offerBookListener.onOfferRemoved((Offer) offerDataObject);
                                    }
                                } catch (ClassNotFoundException | IOException e) {
                                    e.printStackTrace();
                                    log.error("Remove offer from DHT failed. Error: " + e.getMessage());
                                }
                            });
                            writeInvalidationTimestampToDHT(locationKey);
                        });
                    }
                    else {
                        log.error("Remove offer from DHT failed. Cause: future.isSuccess() = false, locationKey: " +
                                locationKey + ", Reason: " + future.failedReason());
                    }
                }

                @Override
                public void exceptionCaught(Throwable t) throws Exception {
                    log.error("Remove offer from DHT failed. Error: " + t.getMessage());
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
            log.error("Remove offer from DHT failed. Error: " + e.getMessage());
        }
    }

    public void getOffers(String currencyCode) {
        Number160 locationKey = Number160.createHash(currencyCode);
        log.trace("Get offers from DHT requested for locationKey: " + locationKey);
        FutureGet futureGet = p2pNode.getDataMap(locationKey);
        futureGet.addListener(new BaseFutureAdapter<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture baseFuture) throws Exception {
                if (isSuccess(baseFuture)) {
                    final Map<Number640, Data> dataMap = futureGet.dataMap();
                    final List<Offer> offers = new ArrayList<>();
                    if (dataMap != null) {
                        for (Data offerData : dataMap.values()) {
                            try {
                                Object offerDataObject = offerData.object();
                                if (offerDataObject instanceof Offer) {
                                    offers.add((Offer) offerDataObject);
                                }
                            } catch (ClassNotFoundException | IOException e) {
                                e.printStackTrace();
                            }
                        }

                        Platform.runLater(() -> offerBookListeners.stream().forEach(listener ->
                                listener.onOffersReceived(offers)));
                    }

                    log.trace("Get offers from DHT was successful. Stored data: [key: " + locationKey
                            + ", values: " + futureGet.dataMap() + "]");
                }
                else {
                    final Map<Number640, Data> dataMap = futureGet.dataMap();
                    if (dataMap == null || dataMap.size() == 0) {
                        log.trace("Get offers from DHT delivered empty dataMap.");
                        Platform.runLater(() -> offerBookListeners.stream().forEach(listener ->
                                listener.onOffersReceived(new ArrayList<>())));
                    }
                    else {
                        log.error("Get offers from DHT  was not successful with reason:" + baseFuture.failedReason());
                    }
                }
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade process
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void sendMessage(Peer peer, Message message,
                            OutgoingMessageListener listener) {
        if (!(peer instanceof TomP2PPeer)) {
            throw new IllegalArgumentException("peer must be of type TomP2PPeer");
        }
        FutureDirect futureDirect = p2pNode.sendData(((TomP2PPeer) peer).getPeerAddress(), message);
        futureDirect.addListener(new BaseFutureListener<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture future) throws Exception {
                if (isSuccess(futureDirect)) {
                    Platform.runLater(listener::onResult);
                }
                else {
                    Platform.runLater(listener::onFailed);
                }
            }

            @Override
            public void exceptionCaught(Throwable t) throws Exception {
                Platform.runLater(listener::onFailed);
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Arbitrators
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addArbitrator(Arbitrator arbitrator) {
        Number160 locationKey = Number160.createHash(ARBITRATORS_ROOT);
        try {
            final Data arbitratorData = new Data(arbitrator);

            FuturePut addFuture = p2pNode.addProtectedData(locationKey, arbitratorData);
            addFuture.addListener(new BaseFutureAdapter<BaseFuture>() {
                @Override
                public void operationComplete(BaseFuture future) throws Exception {
                    Platform.runLater(() -> arbitratorListeners.stream().forEach(listener ->
                    {
                        try {
                            Object arbitratorDataObject = arbitratorData.object();
                            if (arbitratorDataObject instanceof Arbitrator) {
                                listener.onArbitratorAdded((Arbitrator) arbitratorDataObject);
                            }
                        } catch (ClassNotFoundException | IOException e) {
                            e.printStackTrace();
                            log.error(e.toString());
                        }
                    }));

                    if (isSuccess(addFuture)) {
                        log.trace("Add arbitrator to DHT was successful. Stored data: [key: " + locationKey + ", " +
                                "values: " + arbitratorData + "]");
                    }
                    else {
                        log.error("Add arbitrator to DHT failed with reason:" + addFuture.failedReason());
                    }
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void removeArbitrator(Arbitrator arbitrator) throws IOException {
        Number160 locationKey = Number160.createHash(ARBITRATORS_ROOT);
        final Data arbitratorData = new Data(arbitrator);
        FutureRemove removeFuture = p2pNode.removeFromDataMap(locationKey, arbitratorData);
        removeFuture.addListener(new BaseFutureAdapter<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture future) throws Exception {
                Platform.runLater(() -> arbitratorListeners.stream().forEach(listener ->
                {
                    for (Data arbitratorData : removeFuture.dataMap().values()) {
                        try {
                            Object arbitratorDataObject = arbitratorData.object();
                            if (arbitratorDataObject instanceof Arbitrator) {
                                Arbitrator arbitrator = (Arbitrator) arbitratorDataObject;
                                listener.onArbitratorRemoved(arbitrator);
                            }
                        } catch (ClassNotFoundException | IOException e) {
                            e.printStackTrace();
                        }
                    }
                }));
                if (isSuccess(removeFuture)) {
                    log.trace("Remove arbitrator from DHT was successful. Stored data: [key: " + locationKey + ", " +
                            "values: " + arbitratorData + "]");
                }
                else {
                    log.error("Remove arbitrators from DHT failed with reason:" + removeFuture.failedReason());
                }
            }
        });
    }

    public void getArbitrators(Locale languageLocale) {
        Number160 locationKey = Number160.createHash(ARBITRATORS_ROOT);
        FutureGet futureGet = p2pNode.getDataMap(locationKey);
        futureGet.addListener(new BaseFutureAdapter<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture baseFuture) throws Exception {
                Platform.runLater(() -> arbitratorListeners.stream().forEach(listener ->
                {
                    List<Arbitrator> arbitrators = new ArrayList<>();
                    for (Data arbitratorData : futureGet.dataMap().values()) {
                        try {
                            Object arbitratorDataObject = arbitratorData.object();
                            if (arbitratorDataObject instanceof Arbitrator) {
                                arbitrators.add((Arbitrator) arbitratorDataObject);
                            }
                        } catch (ClassNotFoundException | IOException e) {
                            e.printStackTrace();
                            log.error("Get arbitrators from DHT failed with exception:" + e.getMessage());
                        }
                    }

                    listener.onArbitratorsReceived(arbitrators);
                }));
                if (isSuccess(baseFuture)) {
                    log.trace("Get arbitrators from DHT was successful. Stored data: [key: " + locationKey + ", " +
                            "values: " + futureGet.dataMap() + "]");
                }
                else {
                    log.error("Get arbitrators from DHT failed with reason:" + baseFuture.failedReason());
                }
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Event Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addOfferBookListener(OfferBookListener listener) {
        offerBookListeners.add(listener);
    }

    public void removeOfferBookListener(OfferBookListener listener) {
        offerBookListeners.remove(listener);
    }

    public void addArbitratorListener(ArbitratorListener listener) {
        arbitratorListeners.add(listener);
    }

    public void removeArbitratorListener(ArbitratorListener listener) {
        arbitratorListeners.remove(listener);
    }

    public void addIncomingMessageListener(IncomingMessageListener listener) {
        incomingMessageListeners.add(listener);
    }

    public void removeIncomingMessageListener(IncomingMessageListener listener) {
        incomingMessageListeners.remove(listener);
    }


    /*
     * We store the timestamp of any change of the offer list (add, remove offer) and we poll in intervals for changes.
     * If we detect a change we request the offer list from the DHT.
     * Polling should be replaced by a push based solution later.
     */

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Polling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void writeInvalidationTimestampToDHT(Number160 locationKey) {
        invalidationTimestamp.set(System.currentTimeMillis());
        try {
            FuturePut putFuture = p2pNode.putData(getInvalidatedLocationKey(locationKey),
                    new Data(invalidationTimestamp.get()));
            putFuture.addListener(new BaseFutureListener<BaseFuture>() {
                @Override
                public void operationComplete(BaseFuture future) throws Exception {
                    if (isSuccess(putFuture))
                        log.trace("Update invalidationTimestamp to DHT was successful. TimeStamp=" +
                                invalidationTimestamp.get());
                    else
                        log.error("Update invalidationTimestamp to DHT failed with reason:" + putFuture.failedReason());
                }

                @Override
                public void exceptionCaught(Throwable t) throws Exception {
                    log.error("Update invalidationTimestamp to DHT failed with exception:" + t.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Update invalidationTimestamp to DHT failed with exception:" + e.getMessage());
        }
    }

    public LongProperty invalidationTimestampProperty() {
        return invalidationTimestamp;
    }

    public void requestInvalidationTimeStampFromDHT(String currencyCode) {
        Number160 locationKey = Number160.createHash(currencyCode);
        FutureGet getFuture = p2pNode.getData(getInvalidatedLocationKey(locationKey));
        getFuture.addListener(new BaseFutureListener<BaseFuture>() {
            @Override
            public void operationComplete(BaseFuture future) throws Exception {
                if (isSuccess(getFuture)) {
                    Data data = getFuture.data();
                    if (data != null && data.object() instanceof Long) {
                        final Object object = data.object();
                        Platform.runLater(() -> {
                            Long timeStamp = (Long) object;
                            //log.trace("Get invalidationTimestamp from DHT was successful. TimeStamp=" + timeStamp);
                            invalidationTimestamp.set(timeStamp);
                        });
                    }
                    else if (data != null) {
                        log.error("Get invalidationTimestamp from DHT failed. Data = " + data);
                    }
                }
                else if (getFuture.data() == null) {
                    // OK as nothing is set at the moment
                    log.trace("Get invalidationTimestamp from DHT returns null. That is ok for the startup.");
                }
                else {
                    log.error("Get invalidationTimestamp from DHT failed with reason:" + getFuture.failedReason());
                }
            }

            @Override
            public void exceptionCaught(Throwable t) throws Exception {
                log.error("Get invalidationTimestamp from DHT failed with exception:" + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    private Number160 getInvalidatedLocationKey(Number160 locationKey) {
        return Number160.createHash(locationKey + "invalidated");
    }

    // Isolate the success handling as there is bug in port forwarding mode
    private boolean isSuccess(BaseFuture baseFuture) {
        // return baseFuture.isSuccess();
        return true;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handler
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void handleMessage(Object message, Peer sender) {
        if (message instanceof Message) {
            Platform.runLater(() -> incomingMessageListeners.stream().forEach(e ->
                    e.onMessage((Message) message, sender)));
        }
    }
}