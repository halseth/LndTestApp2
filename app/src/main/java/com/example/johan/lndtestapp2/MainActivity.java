package com.example.johan.lndtestapp2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;


import com.squareup.okhttp.ConnectionSpec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.IoUtils;
import io.grpc.okhttp.OkHttpChannelBuilder;
import lndbindings.Lndbindings;
import lnrpc.LightningGrpc;
import lnrpc.*;

public class MainActivity extends AppCompatActivity {

    private Button mSendButton;
    private EditText mHostEdit;
    private EditText mPortEdit;
    private EditText mMessageEdit;
    private TextView mResultText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSendButton = (Button) findViewById(R.id.send_button);
        mHostEdit = (EditText) findViewById(R.id.host_edit_text);
        mPortEdit = (EditText) findViewById(R.id.port_edit_text);
        mMessageEdit = (EditText) findViewById(R.id.message_edit_text);
        mResultText = (TextView) findViewById(R.id.grpc_response_text);
        mResultText.setMovementMethod(new ScrollingMovementMethod());

        start();

    }

    private void start() {
        //final File dataDir = getApplicationContext().getDir("appData", MODE_PRIVATE);
        final File dataDir = getExternalFilesDir(null);
        File conf = new File(dataDir, "lnd.conf");

        System.out.println("datadir:" + dataDir.getAbsolutePath());

        try (FileOutputStream fout = new FileOutputStream(conf)) {
            AssetManager am = getApplicationContext().getAssets();
            InputStream is = am.open("lnd.conf");
            byte[] buffer = new byte[100];
            int l;
            while ( (l = is.read(buffer)) > 0) {
                System.out.println("red: " + String.valueOf(buffer));
                fout.write(buffer, 0, l);
            }
        } catch (IOException e) {
            System.out.println("Failed: " + e);
        }

        Runnable startLnd = new Runnable() {
            @Override
            public void run() {
                System.out.println("starting lnd");
                Lndbindings.start(dataDir.getAbsolutePath());
            }
        };
        new Thread(startLnd).start();
    }

    /**
     * A native method that is implemented by the 'native-lib' native library,
     * which is packaged with this application.
     */
//    public native String stringFromJNI();
//
//    // Used to load the 'native-lib' library on application startup.
//    static {
//        System.loadLibrary("native-lib");
//    }

    public void sendMessage(View view) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(mHostEdit.getWindowToken(), 0);
        mSendButton.setEnabled(false);
        new GrpcTask().execute();
    }

    static class MacaroonCallCredential implements CallCredentials {
        private final String macaroon;

        MacaroonCallCredential(String macaroon) {
            this.macaroon = macaroon;
        }

        public void thisUsesUnstableApi() {}

        public void applyRequestMetadata(
                MethodDescriptor< ? , ? > methodDescriptor,
                Attributes attributes,
                Executor executor,
                final MetadataApplier metadataApplier
        ) {
            String authority = attributes.get(ATTR_AUTHORITY);
            System.out.println(authority);
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        Metadata headers = new Metadata();
                        Metadata.Key < String > macaroonKey = Metadata.Key.of("macaroon", Metadata.ASCII_STRING_MARSHALLER);
                        headers.put(macaroonKey, macaroon);
                        metadataApplier.apply(headers);
                    } catch (Throwable e) {
                        metadataApplier.fail(Status.UNAUTHENTICATED.withCause(e));
                    }
                }
            });
        }
    }

    private final static char[] hexArray = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    private class GrpcTask extends AsyncTask<Void, Void, String> {
        private String mHost;
        private String mMessage;
        private int mPort;
        private ManagedChannel mChannel;

        @Override
        protected void onPreExecute() {
            mHost = mHostEdit.getText().toString();
            mMessage = mMessageEdit.getText().toString();
            String portStr = mPortEdit.getText().toString();
            mPort = TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
            mResultText.setText("");
        }

        @Override
        protected String doInBackground(Void... nothing) {
            final File dataDir = getExternalFilesDir(null);
            File certFile = new File(dataDir, "tls.cert");
            File macaroonFile = new File(dataDir, "admin.macaroon");
            try {

                KeyStore trust_store = KeyStore.getInstance(KeyStore.getDefaultType());
                trust_store.load(null);

                InputStream input_stream = new FileInputStream(certFile);

                CertificateFactory cert_factory = CertificateFactory.getInstance("X.509");
                Certificate cert = cert_factory.generateCertificate(input_stream);
                trust_store.setCertificateEntry("cert", cert);

                TrustManagerFactory trust_manager_factory = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                trust_manager_factory.init(trust_store);
                TrustManager[] trust_manager = trust_manager_factory.getTrustManagers();

                SSLContext tlsContext = SSLContext.getInstance("TLSv1.2");
                tlsContext.init(null, trust_manager, null);

                FileInputStream macaroonStream = new FileInputStream(macaroonFile);

                String macaroon = bytesToHex(IoUtils.toByteArray(macaroonStream));

                ManagedChannel channel = OkHttpChannelBuilder.forAddress(mHost, mPort)
                        .connectionSpec(ConnectionSpec.MODERN_TLS)
                        .sslSocketFactory(tlsContext.getSocketFactory())
                        .build();
//                mChannel = ManagedChannelBuilder.forAddress(mHost, mPort)
//                       // .usePlaintext(true)
//                       // .useTransportSecurity()
//                        .build();

                mChannel = channel;


                Rpc.GetInfoRequest req = Rpc.GetInfoRequest.newBuilder().build();
                LightningGrpc.LightningBlockingStub stub = LightningGrpc.newBlockingStub(mChannel)
                        .withCallCredentials(new MacaroonCallCredential(macaroon));
                Rpc.GetInfoResponse reply = stub.getInfo(req);
                return reply.toString();
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                pw.flush();
                return String.format("Failed... : %n%s", sw);
            }
        }

        @Override
        protected void onPostExecute(String result) {
            try {
                mChannel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            mResultText.setText(result);
            mSendButton.setEnabled(true);
        }
    }
}