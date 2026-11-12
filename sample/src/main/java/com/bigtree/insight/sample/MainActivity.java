package com.bigtree.insight.sample;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.bigtree.insight.Insight;

public final class MainActivity extends FragmentActivity {
    private TextView log;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        log = findViewById(R.id.log);
        Insight.setListener((type, summary) -> log.append(summary + "\n"));
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.fragment_slot, new TimingFragment())
                    .commit();
        }
        findViewById(R.id.open_second).setOnClickListener(v ->
                startActivity(new Intent(this, SecondActivity.class)));
        findViewById(R.id.slow_task).setOnClickListener(v ->
                Insight.trace("主线程慢任务", () -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }));
        findViewById(R.id.java_crash).setOnClickListener(v -> {
            throw new IllegalStateException("sample java crash");
        });
        findViewById(R.id.native_crash).setOnClickListener(v -> CrashTrigger.sigsegv());
        findViewById(R.id.show_pending).setOnClickListener(v -> showPending());
    }

    private void showPending() {
        log.append("--- pending ---\n");
        for (String line : Insight.pendingSummaries()) {
            log.append(line);
            log.append("\n");
        }
    }
}
