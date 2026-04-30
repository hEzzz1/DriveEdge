package com.driveedge.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.driveedge.app.R;
import com.driveedge.app.edge.EdgeContextStore;
import com.driveedge.app.edge.EdgeFlowController;
import com.driveedge.app.edge.EdgeLocalContext;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VehicleWaitingActivity extends AppCompatActivity {
  private static final long POLL_INTERVAL_MS = 5000L;

  private final ExecutorService edgeIoExecutor = Executors.newSingleThreadExecutor();
  private final Handler pollHandler = new Handler(Looper.getMainLooper());
  private final EdgeFlowController edgeFlowController = new EdgeFlowController();
  @Nullable
  private EdgeContextStore edgeContextStore;
  private EdgeLocalContext edgeLocalContext = EdgeLocalContext.empty();

  private TextView enterpriseView;
  private TextView waitingMessageView;
  private Button refreshButton;

  private final Runnable pollRunnable = new Runnable() {
    @Override
    public void run() {
      refreshStatus(true);
      pollHandler.postDelayed(this, POLL_INTERVAL_MS);
    }
  };

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_vehicle_waiting);

    enterpriseView = findViewById(R.id.enterpriseView);
    waitingMessageView = findViewById(R.id.waitingMessageView);
    refreshButton = findViewById(R.id.refreshButton);

    edgeContextStore = new EdgeContextStore(getApplicationContext());
    edgeLocalContext = edgeContextStore.load();
    refreshButton.setOnClickListener(v -> refreshStatus(false));

    if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, edgeLocalContext, VehicleWaitingActivity.class)) {
      return;
    }
    render();
    refreshStatus(true);
  }

  @Override
  protected void onResume() {
    super.onResume();
    pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
  }

  @Override
  protected void onPause() {
    pollHandler.removeCallbacks(pollRunnable);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    pollHandler.removeCallbacks(pollRunnable);
    edgeIoExecutor.shutdownNow();
    super.onDestroy();
  }

  private void refreshStatus(boolean silentFailure) {
    refreshButton.setEnabled(false);
    edgeIoExecutor.execute(() -> {
      EdgeContextStore store = edgeContextStore;
      if (store == null) {
        return;
      }
      try {
        EdgeLocalContext updated = edgeFlowController.syncContext(store.load());
        store.save(updated);
        edgeLocalContext = updated;
        runOnUiThread(() -> {
          if (EdgeRoutes.routeIfNeeded(this, edgeFlowController, updated, VehicleWaitingActivity.class)) {
            return;
          }
          render();
          refreshButton.setEnabled(true);
        });
      } catch (Exception error) {
        runOnUiThread(() -> {
          if (!silentFailure) {
            Toast.makeText(this, edgeFlowController.formatEdgeError(edgeLocalContext, error), Toast.LENGTH_SHORT).show();
          }
          render();
          refreshButton.setEnabled(true);
        });
      }
    });
  }

  private void render() {
    EdgeContextStore store = edgeContextStore;
    if (store != null) {
      edgeLocalContext = store.load();
    }
    enterpriseView.setText(getString(
      R.string.edge_vehicle_waiting_enterprise,
      EdgeFlowController.displayValue(edgeLocalContext.enterpriseName, edgeLocalContext.enterpriseId)
    ));
    waitingMessageView.setText(edgeFlowController.resolveStatusMessage(edgeLocalContext));
  }
}
