package io.bisq.seednode;

import ch.qos.logback.classic.Level;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import io.bisq.common.CommonOptionKeys;
import io.bisq.common.UserThread;
import io.bisq.common.app.Capabilities;
import io.bisq.common.app.Log;
import io.bisq.common.app.Version;
import io.bisq.common.crypto.LimitedKeyStrengthException;
import io.bisq.common.handlers.ResultHandler;
import io.bisq.common.locale.CurrencyUtil;
import io.bisq.common.locale.Res;
import io.bisq.common.util.Utilities;
import io.bisq.core.app.*;
import io.bisq.core.arbitration.ArbitratorManager;
import io.bisq.core.btc.BaseCurrencyNetwork;
import io.bisq.core.btc.wallet.BsqWalletService;
import io.bisq.core.btc.wallet.BtcWalletService;
import io.bisq.core.btc.wallet.WalletsSetup;
import io.bisq.core.dao.DaoOptionKeys;
import io.bisq.core.offer.OpenOfferManager;
import io.bisq.network.p2p.P2PService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.bitcoinj.store.BlockStoreException;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;

@Slf4j
public class SeedNode {
    // Bisq v0.6.1 did not change anything relevant for that project so we stick with 0.6.0
    public static final String VERSION = "0.6.1";

    private static BisqEnvironment bisqEnvironment;
    public static void setEnvironment(BisqEnvironment bisqEnvironment) {
        SeedNode.bisqEnvironment = bisqEnvironment;
    }

    private final Injector injector;
    private final SeedNodeModule seedNodeModule;
    private final AppSetup appSetup;

    public SeedNode() {
        String logPath = Paths.get(bisqEnvironment.getProperty(AppOptionKeys.APP_DATA_DIR_KEY), "bisq").toString();
        Log.setup(logPath);
        Log.setLevel(Level.toLevel(bisqEnvironment.getRequiredProperty(CommonOptionKeys.LOG_LEVEL_KEY)));

        log.info("Log files under: " + logPath);
        log.info("SeedNode.VERSION: " + SeedNode.VERSION);
        log.info("Bisq exchange Version{" +
                "VERSION=" + Version.VERSION +
                ", P2P_NETWORK_VERSION=" + Version.P2P_NETWORK_VERSION +
                ", LOCAL_DB_VERSION=" + Version.LOCAL_DB_VERSION +
                ", TRADE_PROTOCOL_VERSION=" + Version.TRADE_PROTOCOL_VERSION +
                ", BASE_CURRENCY_NETWORK=NOT SET"   +
                ", getP2PNetworkId()=NOT SET"  +
                '}');
        Utilities.printSysInfo();

        // setup UncaughtExceptionHandler
        Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
            // Might come from another thread
            if (throwable.getCause() != null && throwable.getCause().getCause() != null &&
                    throwable.getCause().getCause() instanceof BlockStoreException) {
                log.error(throwable.getMessage());
            } else {
                log.error("Uncaught Exception from thread " + Thread.currentThread().getName());
                log.error("throwableMessage= " + throwable.getMessage());
                log.error("throwableClass= " + throwable.getClass());
                log.error("Stack trace:\n" + ExceptionUtils.getStackTrace(throwable));
                throwable.printStackTrace();
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
        Thread.currentThread().setUncaughtExceptionHandler(handler);

        try {
            Utilities.checkCryptoPolicySetup();
        } catch (NoSuchAlgorithmException | LimitedKeyStrengthException e) {
            e.printStackTrace();
            UserThread.execute(this::shutDown);
        }
        Security.addProvider(new BouncyCastleProvider());

        final BaseCurrencyNetwork baseCurrencyNetwork = BisqEnvironment.getBaseCurrencyNetwork();
        final String currencyCode = baseCurrencyNetwork.getCurrencyCode();
        Res.setBaseCurrencyCode(currencyCode);
        Res.setBaseCurrencyName(baseCurrencyNetwork.getCurrencyName());
        CurrencyUtil.setBaseCurrencyCode(currencyCode);

        seedNodeModule = new SeedNodeModule(bisqEnvironment);
        injector = Guice.createInjector(seedNodeModule);

        Boolean fullDaoNode = injector.getInstance(Key.get(Boolean.class, Names.named(DaoOptionKeys.FULL_DAO_NODE)));
        appSetup = fullDaoNode ? injector.getInstance(AppSetupWithP2PAndDAO.class) : injector.getInstance(AppSetupWithP2P.class);
        if (fullDaoNode)
            Capabilities.setSupportedCapabilities(new ArrayList<>(Arrays.asList(
                    Capabilities.Capability.TRADE_STATISTICS.ordinal(),
                    Capabilities.Capability.TRADE_STATISTICS_2.ordinal(),
                    Capabilities.Capability.ACCOUNT_AGE_WITNESS.ordinal(),
                    Capabilities.Capability.SEED_NODE.ordinal(),
                    Capabilities.Capability.DAO_FULL_NODE.ordinal()
            )));
        else
            Capabilities.setSupportedCapabilities(new ArrayList<>(Arrays.asList(
                    Capabilities.Capability.TRADE_STATISTICS.ordinal(),
                    Capabilities.Capability.TRADE_STATISTICS_2.ordinal(),
                    Capabilities.Capability.ACCOUNT_AGE_WITNESS.ordinal(),
                    Capabilities.Capability.SEED_NODE.ordinal()
            )));
        appSetup.start();
    }

    private void shutDown() {
        gracefulShutDown(() -> {
            log.debug("Shutdown complete");
            System.exit(0);
        });
    }

    public void gracefulShutDown(ResultHandler resultHandler) {
        log.debug("gracefulShutDown");
        try {
            if (injector != null) {
                injector.getInstance(ArbitratorManager.class).shutDown();
                injector.getInstance(OpenOfferManager.class).shutDown(() -> injector.getInstance(P2PService.class).shutDown(() -> {
                    injector.getInstance(WalletsSetup.class).shutDownComplete.addListener((ov, o, n) -> {
                        seedNodeModule.close(injector);
                        log.debug("Graceful shutdown completed");
                        resultHandler.handleResult();
                    });
                    injector.getInstance(WalletsSetup.class).shutDown();
                    injector.getInstance(BtcWalletService.class).shutDown();
                    injector.getInstance(BsqWalletService.class).shutDown();
                }));
                // we wait max 5 sec.
                UserThread.runAfter(resultHandler::handleResult, 5);
            } else {
                UserThread.runAfter(resultHandler::handleResult, 1);
            }
        } catch (Throwable t) {
            log.debug("App shutdown failed with exception");
            t.printStackTrace();
            System.exit(1);
        }
    }
}
