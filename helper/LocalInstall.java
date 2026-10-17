import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs as shell through app_process; never installed as an Android application. */
public final class LocalInstall {
    private static String stage = "startup";

    private static void stage(String value) {
        stage = value;
        System.out.println("LOCALINSTALL_STAGE=" + value);
    }

    public static void main(String[] args) {
        int exitCode = 1;
        PackageInstaller.Session session = null;
        boolean committed = false;
        try {
            if (args.length < 1) {
                throw new IllegalArgumentException("Usage: LocalInstall <apk> [timeout-seconds]");
            }
            File apk = new File(args[0]);
            if (!apk.isFile()) {
                throw new IllegalArgumentException("APK file not found: " + apk);
            }
            long timeoutSeconds = args.length > 1 ? Long.parseLong(args[1]) : 120;
            if (timeoutSeconds <= 0) {
                throw new IllegalArgumentException("Timeout must be positive");
            }

            stage("system-context");
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();

            stage("create-session");
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(apk.length());
            int sessionId = installer.createSession(params);
            System.out.println("LOCALINSTALL_SESSION=" + sessionId);
            session = installer.openSession(sessionId);

            stage("copy-apk");
            try (FileInputStream input = new FileInputStream(apk);
                    OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                session.fsync(output);
            }

            ResultSender receiver = new ResultSender();
            IntentSender sender = IntentSender.class.getDeclaredConstructor(IIntentSender.class)
                    .newInstance(receiver);
            stage("commit");
            session.commit(sender);
            committed = true;

            stage("wait");
            if (!receiver.ready.await(timeoutSeconds, TimeUnit.SECONDS)) {
                System.out.println("LOCALINSTALL_TIMEOUT=" + timeoutSeconds);
                exitCode = 2;
            } else {
                Intent result = receiver.result;
                if (result == null) {
                    throw new IllegalStateException("PackageInstaller returned an empty callback");
                }
                int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
                int legacy = result.getIntExtra("android.content.pm.extra.LEGACY_STATUS", Integer.MIN_VALUE);
                System.out.println("LOCALINSTALL_STATUS=" + status);
                System.out.println("LOCALINSTALL_LEGACY_STATUS=" + legacy);
                String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    message = "Android requires confirmation on the head unit (STATUS_PENDING_USER_ACTION). "
                            + (message == null ? "" : message);
                }
                System.out.println("LOCALINSTALL_MESSAGE=" + oneLine(message));
                System.out.println("LOCALINSTALL_PACKAGE="
                        + oneLine(result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)));
                exitCode = status == PackageInstaller.STATUS_SUCCESS ? 0 : 1;
            }
        } catch (Throwable error) {
            while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) {
                error = error.getCause();
            }
            System.out.println("LOCALINSTALL_EXCEPTION=" + oneLine(error.toString()));
            error.printStackTrace(System.out);
        } finally {
            if (session != null) {
                try {
                    if (!committed) {
                        session.abandon();
                    }
                    session.close();
                } catch (Throwable cleanupError) {
                    System.out.println("LOCALINSTALL_CLEANUP=" + oneLine(cleanupError.toString()));
                }
            }
        }
        System.out.flush();
        System.exit(exitCode);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static final class ResultSender extends IIntentSender.Stub {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile Intent result;

        @Override
        public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
            result = intent;
            ready.countDown();
        }
    }
}
