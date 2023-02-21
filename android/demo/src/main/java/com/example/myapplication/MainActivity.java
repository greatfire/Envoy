package com.example.myapplication;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import org.chromium.net.CronetEngine;
import org.greatfire.envoy.CronetNetworking;
import org.greatfire.envoy.NetworkIntentService;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.greatfire.envoy.NetworkIntentServiceKt.ENVOY_BROADCAST_VALIDATION_SUCCEEDED;
import static org.greatfire.envoy.NetworkIntentServiceKt.ENVOY_DATA_URLS_SUCCEEDED;

public class MainActivity extends FragmentActivity {

    private static final String TAG = "EnvoyDemoApp";

    private ViewPager2 viewPager;

    private static final String[] titles = new String[]{"Cronet", "OKHttp", "WebView", "Volley", "HttpConn", "Nuke"};

    NetworkIntentService mService;
    boolean mBound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // register to receive test results
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, new IntentFilter(ENVOY_BROADCAST_VALIDATION_SUCCEEDED));

        viewPager = findViewById(R.id.pager);
        // viewPager.setPageTransformer();
        FragmentStateAdapter pagerAdapter = new MyFragmentStateAdapter(this);
        viewPager.setAdapter(pagerAdapter);

        TabLayout tabLayout = findViewById(R.id.tab_layout);
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(titles[position])
        ).attach();

        // String url = "https://ifconfig.co/ip";
        // url = "https://httpbin.org/ip";
        String envoyUrl = "socks5://127.0.0.1:1080"; // Keep this if no port conflicts

        List<String> envoyUrls = Collections.unmodifiableList(Arrays.asList(envoyUrl, "https://allowed.example.com/path/"));
        NetworkIntentService.submit(this, envoyUrls);
        // we will get responses in NetworkIntentServiceReceiver's onReceive

        // NetworkIntentService.enqueueQuery(this); // async
        if (mBound) {
            Log.i(TAG, "current valid urls are " + mService.getValidUrls()); // sync
        }

        CronetEngine.Builder engineBuilder = new CronetEngine.Builder(getApplication());
        engineBuilder.setUserAgent("curl/7.66.0");
        engineBuilder.setEnvoyUrl(envoyUrl);

        CronetEngine engine = engineBuilder.build();
        Log.d(TAG, "engine version " + engine.getVersionString());
        //File outputFile = File.createTempFile("cronet", "log",
        //    Environment.getExternalStorageDirectory());
        //engine.startNetLogToFile(outputFile.toString(), false);
/*        Executor executor = Executors.newSingleThreadExecutor();
        CronetFragment.MyUrlRequestCallback callback = new CronetFragment.MyUrlRequestCallback();
        UrlRequest.Builder requestBuilder = engine.newUrlRequestBuilder(
                url, callback, executor);
        UrlRequest request = requestBuilder.addHeader("My-Header1", "value1").build();
        request.start();*/
        // https://stackoverflow.com/questions/41110684/cronet-and-experimentalcronetengine
        // URL.setURLStreamHandlerFactory(new CronetURLStreamHandlerFactory((ExperimentalCronetEngine)engine));

        // new RetrofitRequestTask().execute(url);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    public void onBackPressed() {
        if (viewPager.getCurrentItem() == 0) {
            // If the user is currently looking at the first step, allow the system to handle the
            // Back button. This calls finish() on this activity and pops the back stack.
            super.onBackPressed();
        } else {
            // Otherwise, select the previous step.
            viewPager.setCurrentItem(viewPager.getCurrentItem() - 1);
        }
    }

    static class MyFragmentStateAdapter extends FragmentStateAdapter {

        MyFragmentStateAdapter(FragmentActivity fa) {
            super(fa);
        }

        @NotNull
        @Override
        public Fragment createFragment(int position) {
            Log.d(TAG, "fragment at position " + position);
            switch (position) {
                case 0:
                    return new CronetFragment();
                case 1:
                    return new OkHttpFragment();
                case 2:
                    return new WebViewFragment();
                case 3:
                    return new VolleyFragment();
                case 4:
                    return new HttpURLConnectionFragment();
                case 5:
                    return new SimpleFragment();
                default:
                    return BaseFragment.newInstance(position);
            }
        }

        @Override
        public int getItemCount() {
            return titles.length;
        }

        public static class SimpleFragment extends Fragment {
            public SimpleFragment() {
                // Required empty public constructor
            }

            @Nullable
            @Override
            public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
                LinearLayout linearLayout = new LinearLayout(getContext());
                linearLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
                linearLayout.setOrientation(LinearLayout.HORIZONTAL);
                linearLayout.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
                TextView textView = new TextView(getContext());
                // textView.setInputType(EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE);
                textView.setText(R.string.nuke_instruction);
                linearLayout.addView(textView);
                return linearLayout;
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to NetworkIntentService
        Intent intent = new Intent(this, NetworkIntentService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unbindService(connection);
        mBound = false;
    }

    /**
     * Defines callbacks for service binding, passed to bindService()
     */
    private final ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            NetworkIntentService.NetworkBinder binder = (NetworkIntentService.NetworkBinder) service;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    protected final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        private static final String TAG = "NetworkReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                final List<String> validUrls = intent.getStringArrayListExtra(ENVOY_DATA_URLS_SUCCEEDED);
                if (validUrls != null && !validUrls.isEmpty()) {
                    String envoyUrl = validUrls.get(0);
                    Log.i(TAG, "Received valid urls: " + TextUtils.join(", ", validUrls));
                    // Select the fastest one
                    CronetNetworking.initializeCronetEngine(context, envoyUrl); // reInitializeIfNeeded set to false
                    new Thread() {
                        @Override
                        public void run() {
                            try {
                                URL url = new URL("https://api.ipify.org/");
                                BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
                                String inputLine;
                                StringBuilder response = new StringBuilder();
                                while ((inputLine = in.readLine()) != null)
                                    response.append(inputLine);
                                in.close();

                                Log.d(TAG, "proxied request returns " + response.toString());
                            } catch (MalformedURLException e) {
                                Log.e(TAG, "failed to proxy request ", e);
                            } catch (IOException e) {
                                Log.e(TAG, "failed to read response ", e);
                            }
                        }
                    }.start();
                    runOnUiThread(() -> {
                        // MainActivity.this.setTitle("V " + validUrls.toString());
                    });
                } else {
                    Log.e(TAG, "Received empty valid urls");
                }
            }
        }
    };
}
