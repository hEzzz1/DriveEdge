package com.driveedge.app.ui;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.driveedge.app.edge.EdgeFlowController;
import com.driveedge.app.edge.EdgeLocalContext;

final class EdgeRoutes {
  private EdgeRoutes() {
  }

  static boolean routeIfNeeded(
    @NonNull Activity activity,
    @NonNull EdgeFlowController flowController,
    @NonNull EdgeLocalContext context,
    @NonNull Class<?> currentActivityClass
  ) {
    Class<?> target = toActivityClass(flowController.resolveDestination(context));
    if (target == currentActivityClass) {
      return false;
    }
    Intent intent = new Intent(activity, target);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
    activity.startActivity(intent);
    activity.finish();
    return true;
  }

  @NonNull
  private static Class<?> toActivityClass(@NonNull EdgeFlowController.Destination destination) {
    switch (destination) {
      case CLAIM_ENTERPRISE:
        return EdgeLaunchActivity.class;
      case WAITING_VEHICLE:
        return VehicleWaitingActivity.class;
      case SIGN_IN:
        return SignInActivity.class;
      case DISABLED:
        return EdgeDisabledActivity.class;
      case CAPTURE:
      default:
        return MainActivity.class;
    }
  }
}
