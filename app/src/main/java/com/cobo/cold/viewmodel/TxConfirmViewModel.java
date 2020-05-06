/*
 * Copyright (c) 2020 Cobo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * in the file COPYING.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.cobo.cold.viewmodel;

import android.app.Application;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.cobo.coinlib.Util;
import com.cobo.coinlib.coins.AbsCoin;
import com.cobo.coinlib.coins.AbsDeriver;
import com.cobo.coinlib.coins.AbsTx;
import com.cobo.coinlib.coins.BTC.Btc;
import com.cobo.coinlib.coins.BTC.BtcImpl;
import com.cobo.coinlib.coins.BTC.Electrum.ElectrumTx;
import com.cobo.coinlib.coins.BTC.UtxoTx;
import com.cobo.coinlib.exception.InvalidPathException;
import com.cobo.coinlib.exception.InvalidTransactionException;
import com.cobo.coinlib.interfaces.SignCallback;
import com.cobo.coinlib.interfaces.Signer;
import com.cobo.coinlib.path.AddressIndex;
import com.cobo.coinlib.path.CoinPath;
import com.cobo.coinlib.utils.Coins;
import com.cobo.cold.AppExecutors;
import com.cobo.cold.DataRepository;
import com.cobo.cold.MainApplication;
import com.cobo.cold.Utilities;
import com.cobo.cold.callables.ClearTokenCallable;
import com.cobo.cold.callables.GetMessageCallable;
import com.cobo.cold.callables.GetPasswordTokenCallable;
import com.cobo.cold.callables.VerifyFingerprintCallable;
import com.cobo.cold.db.entity.AddressEntity;
import com.cobo.cold.db.entity.CoinEntity;
import com.cobo.cold.db.entity.TxEntity;
import com.cobo.cold.encryption.ChipSigner;
import com.cobo.cold.ui.views.AuthenticateModal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.spongycastle.util.encoders.Hex;

import java.security.SignatureException;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static com.cobo.cold.viewmodel.ElectrumViewModel.parseElectrumTxHex;

public class TxConfirmViewModel extends AndroidViewModel {

    public static final String STATE_SIGNING = "signing";
    public static final String STATE_SIGN_FAIL = "signing_fail";
    public static final String STATE_SIGN_SUCCESS = "signing_success";
    public static final String TAG = "Vault.TxConfirm";
    private final DataRepository mRepository;
    private final MutableLiveData<TxEntity> observableTx = new MutableLiveData<>();
    private final MutableLiveData<Exception> parseTxException = new MutableLiveData<>();
    private final MutableLiveData<Boolean> addingAddress = new MutableLiveData<>();
    private AbsTx transaction;
    private String coinCode;
    private final MutableLiveData<String> signState = new MutableLiveData<>();
    private AuthenticateModal.OnVerify.VerifyToken token;

    public TxConfirmViewModel(@NonNull Application application) {
        super(application);
        observableTx.setValue(null);
        mRepository = MainApplication.getApplication().getRepository();
    }

    public MutableLiveData<TxEntity> getObservableTx() {
        return observableTx;
    }

    public MutableLiveData<Exception> parseTxException() {
        return parseTxException;
    }

    public void parseTxData(String json) {
        AppExecutors.getInstance().diskIO().execute(() -> {
            try {
                JSONObject object = new JSONObject(json);
                Log.i(TAG, "object = " + object.toString(4));
                transaction = AbsTx.newInstance(object);
                if (transaction == null) {
                    observableTx.postValue(null);
                    parseTxException.postValue(new InvalidTransactionException("invalid transaction"));
                    return;
                }
                if (transaction instanceof UtxoTx) {
                    if (!checkChangeAddress(transaction)) {
                        observableTx.postValue(null);
                        parseTxException.postValue(new InvalidTransactionException("invlid change address"));
                        return;
                    }
                }
                TxEntity tx = generateTxEntity(object);
                observableTx.postValue(tx);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
    }

    private TxEntity generateTxEntity(JSONObject object) throws JSONException {
        TxEntity tx = new TxEntity();
        NumberFormat nf = NumberFormat.getInstance();
        nf.setMaximumFractionDigits(20);
        coinCode = Objects.requireNonNull(transaction).getCoinCode();
        tx.setSignId(object.getString("signId"));
        tx.setTimeStamp(object.getLong("timestamp"));
        tx.setCoinCode(coinCode);
        tx.setCoinId(Coins.coinIdFromCoinCode(coinCode));
        tx.setFrom(getFromAddress());
        tx.setTo(getToAddress());
        tx.setAmount(nf.format(transaction.getAmount()) + " " + transaction.getUnit());
        tx.setFee(nf.format(transaction.getFee()) + " " + coinCode);
        tx.setMemo(transaction.getMemo());
        tx.setBelongTo(mRepository.getBelongTo());
        return tx;
    }

    public void parseTxnData(String txnData) {
        AppExecutors.getInstance().networkIO().execute(()-> {
            try {
                JSONObject signTx = parseElectrumTxHex(txnData);
                parseTxData(signTx.toString());
            } catch (ElectrumTx.SerializationException | JSONException e) {
                e.printStackTrace();
            }
        });
    }

    private boolean checkChangeAddress(AbsTx utxoTx) {
        UtxoTx.ChangeAddressInfo changeAddressInfo = ((UtxoTx) utxoTx).getChangeAddressInfo();
        if (changeAddressInfo == null) {
            return true;
        }
        String hdPath = changeAddressInfo.hdPath;
        String address = changeAddressInfo.address;
        String exPub = mRepository.loadCoinEntityByCoinCode(utxoTx.getCoinCode()).getExPub();
        AbsDeriver deriver = AbsDeriver.newInstance(utxoTx.getCoinCode());

        try {
            AddressIndex addressIndex = CoinPath.parsePath(hdPath);
            int change = addressIndex.getParent().getValue();
            int index = addressIndex.getValue();
            String expectAddress = Objects.requireNonNull(deriver).derive(exPub, change, index);
            return address.equals(expectAddress);
        } catch (InvalidPathException e) {
            e.printStackTrace();
            return false;
        }
    }

    private String getToAddress() {
        String to = transaction.getTo();

        if (transaction instanceof UtxoTx) {
            to = ((UtxoTx) transaction).getOutputs().toString();
        }

        return to;
    }

    private String getFromAddress() {
        if (!TextUtils.isEmpty(transaction.getFrom())) {
            return transaction.getFrom();
        }
        String[] paths = transaction.getHdPath().split(AbsTx.SEPARATOR);
        String[] externalPath = Stream.of(paths)
                .filter(this::isExternalPath)
                .toArray(String[]::new);
        ensureAddressExist(externalPath);

        try {
            if (transaction instanceof UtxoTx) {
                JSONArray inputsClone = new JSONArray();
                JSONArray inputs = ((UtxoTx) transaction).getInputs();

                CoinEntity coin = mRepository.loadCoinSync(Coins.coinIdFromCoinCode(coinCode));
                String expub = mRepository.loadAccountsForCoin(coin).get(0).getExPub();

                for (int i = 0; i < inputs.length(); i++) {
                    JSONObject input = inputs.getJSONObject(i);
                    long value = input.getJSONObject("utxo").getLong("value");
                    String hdpath = input.getString("ownerKeyPath");
                    AddressIndex addressIndex = CoinPath.parsePath(hdpath);
                    int index = addressIndex.getValue();
                    int change = addressIndex.getParent().getValue();

                    String from = AbsDeriver.newInstance(transaction.getCoinCode()).derive(expub,change,index);
                    inputsClone.put(new JSONObject().put("value", value)
                                                    .put("address",from));
                }

                return inputsClone.toString();
            }
        } catch (JSONException e) {
            e.printStackTrace();
        } catch (InvalidPathException e) {
            e.printStackTrace();
        }


        return Stream.of(externalPath)
                .distinct()
                .map(path -> mRepository.loadAddressBypath(path).getAddressString())
                .reduce((s1, s2) -> s1 + AbsTx.SEPARATOR + s2)
                .orElse("");
    }

    public static <T> T[] concat(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;

    }

    private boolean isExternalPath(@NonNull String path) {
        try {
            return CoinPath.parsePath(path).getParent().isExternal();
        } catch (InvalidPathException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isInternalPath(@NonNull String path) {
        try {
            return !CoinPath.parsePath(path).getParent().isExternal();
        } catch (InvalidPathException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void ensureAddressExist(String[] paths) {
        if (paths == null || paths.length == 0) {
            return;
        }
        String maxIndexHdPath = paths[0];
        if (paths.length > 1) {
            int max = getAddressIndex(paths[0]);
            for (String path : paths) {
                if (getAddressIndex(path) > max) {
                    max = getAddressIndex(path);
                    maxIndexHdPath = path;
                }
            }
        }
        AddressEntity address = mRepository.loadAddressBypath(maxIndexHdPath);
        if (address == null) {
            addAddress(getAddressIndex(maxIndexHdPath));
        }
    }

    public LiveData<Boolean> getAddingAddressState() {
        return addingAddress;
    }

    private void addAddress(int addressIndex) {
        CoinEntity coin = mRepository.loadCoinSync(Coins.coinIdFromCoinCode(coinCode));
        int addressLength = mRepository.loadAccountsForCoin(coin).get(0).getAddressLength();

        if (addressLength < addressIndex + 1) {
            String[] names = new String[addressIndex + 1 - addressLength];
            int index = 0;
            for (int i = addressLength; i < addressIndex + 1; i++) {
                names[index++] = coinCode + "-" + (i + 1);
            }
            final CountDownLatch mLatch = new CountDownLatch(1);
            addingAddress.postValue(true);
            new AddAddressViewModel.AddAddressTask(coin, mRepository, mLatch::countDown)
                    .execute(names);
            try {
                mLatch.await();
                addingAddress.postValue(false);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    private int getAddressIndex(String hdPath) {
        try {
            return CoinPath.parsePath(hdPath).getValue();
        } catch (InvalidPathException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public MutableLiveData<String> getSignState() {
        return signState;
    }

    public void setToken(AuthenticateModal.OnVerify.VerifyToken token) {
        this.token = token;
    }

    public void handleSign() {
        AppExecutors.getInstance().diskIO().execute(() -> {
            Signer[] signer = initSigners();
            SignCallback callback = initSignCallback();
            signTransaction(transaction, callback, signer);
        });

    }

    private SignCallback initSignCallback() {
        return new SignCallback() {
            @Override
            public void startSign() {
                signState.postValue(STATE_SIGNING);
            }

            @Override
            public void onFail() {
                signState.postValue(STATE_SIGN_FAIL);
                new ClearTokenCallable().call();
            }

            @Override
            public void onSuccess(String txId, String rawTx) {
                TxEntity tx = observableTx.getValue();
                Objects.requireNonNull(tx).setTxId(txId);
                tx.setSignedHex(rawTx);
                mRepository.insertTx(tx);
                signState.postValue(STATE_SIGN_SUCCESS);
                if (Coins.showPublicKey(tx.getCoinCode())) {
                    persistAddress(tx.getCoinCode(), tx.getCoinId(), tx.getFrom());
                }
                new ClearTokenCallable().call();
            }

            @Override
            public void postProgress(int progress) {

            }
        };
    }

    private void persistAddress(String coinCode, String coinId, String address) {
        String path;
        switch (coinCode) {
            case "EOS":
                path = "M/44'/194'/0'/0/0";
                break;
            case "IOST":
                path = "M/44'/291'/0'/0'/0'";
                break;
            default:
                return;
        }
        AddressEntity addressEntity = new AddressEntity();
        addressEntity.setPath(path);
        addressEntity.setAddressString(address);
        addressEntity.setCoinId(coinId);
        addressEntity.setIndex(0);
        addressEntity.setName(coinCode + "-0");
        addressEntity.setBelongTo(Utilities.getCurrentBelongTo(getApplication()));
        mRepository.insertAddress(addressEntity);
    }

    private void signTransaction(@NonNull AbsTx transaction, @NonNull SignCallback callback, Signer... signer) {
        callback.startSign();
        if (signer == null) {
            callback.onFail();
            return;
        }
        switch (transaction.getTxType()) {
            case "OMNI":
            case "OMNI_USDT":
                Btc btc = new Btc(new BtcImpl());
                btc.generateOmniTx(transaction, callback, signer);
                break;
            default:
                AbsCoin coin = AbsCoin.newInstance(coinCode);
                Objects.requireNonNull(coin).generateTransaction(transaction, callback, signer);
        }
    }

    private Signer[] initSigners() {
        String[] paths = transaction.getHdPath().split(AbsTx.SEPARATOR);
        String coinCode = transaction.getCoinCode();
        String[] distinctPaths = Stream.of(paths).distinct().toArray(String[]::new);
        Signer[] signer = new Signer[distinctPaths.length];
        boolean shouldProvidePublicKey = Signer.shouldProvidePublicKey(transaction.getCoinCode());
        String exPub = null;
        if (shouldProvidePublicKey) {
            exPub = mRepository.loadCoinSync(Coins.coinIdFromCoinCode(coinCode)).getExPub();
        }

        String authToken = getAuthToken();
        if (TextUtils.isEmpty(authToken)) {
            Log.w(TAG,"authToken null");
            return null;
        }

        for (int i = 0; i < distinctPaths.length; i++) {
            if (shouldProvidePublicKey) {
                String pubKey;
                if (Coins.curveFromCoinCode(coinCode) == Coins.CURVE.ED25519) {
                    pubKey = Util.getPublicKeyHex(exPub).substring(2);
                } else {
                    pubKey = Util.getPublicKeyHex(exPub, distinctPaths[i]);
                }
                signer[i] = new ChipSigner(distinctPaths[i].toLowerCase(), authToken, pubKey);
            } else {
                signer[i] = new ChipSigner(distinctPaths[i].toLowerCase(), authToken);
            }
        }
        return signer;
    }

    private String getAuthToken() {
        String authToken = null;
        if (!TextUtils.isEmpty(token.password)) {
            authToken = new GetPasswordTokenCallable(token.password).call();
        } else if(token.signature != null) {
            String message = new GetMessageCallable().call();
            if (!TextUtils.isEmpty(message)) {
                try {
                    token.signature.update(Hex.decode(message));
                    byte[] signature = token.signature.sign();
                    byte[] rs = Util.decodeRSFromDER(signature);
                    if (rs != null) {
                        authToken = new VerifyFingerprintCallable(Hex.toHexString(rs)).call();
                    }
                } catch (SignatureException e) {
                    e.printStackTrace();
                }
            }
        }
        AuthenticateModal.OnVerify.VerifyToken.invalid(token);
        return authToken;
    }

    public String getTxId() {
        return Objects.requireNonNull(observableTx.getValue()).getTxId();
    }

    public String getTxHex() {
        return Objects.requireNonNull(observableTx.getValue()).getSignedHex();
    }

    private final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    public boolean isAddressInWhiteList(String address) {
        try {
            return sExecutor.submit(() -> mRepository.queryWhiteList(address) != null).get();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }
}
