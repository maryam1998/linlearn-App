package com.linglearn.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.linglearn.app.overlay.BubblePlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BubblePlugin.class);
        super.onCreate(savedInstanceState);
    }
}