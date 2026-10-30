package ai.petonboard.app

import ai.petonboard.app.logic.PetModeViewModel
import ai.petonboard.app.ui.PetOnboardApp
import ai.petonboard.app.ui.PetOnboardTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {

    private val vm: PetModeViewModel by viewModels()

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[SPEED_PERMISSION] == true) vm.onSpeedPermissionGranted()
        if (result[Manifest.permission.RECORD_AUDIO] == true) vm.onMicPermissionGranted()
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) vm.onLocationPermissionGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PetOnboardTheme { PetOnboardApp(vm) } }

        // 속도(android.car.permission.CAR_SPEED), 마이크(음성 답), 위치(휴게소 고르기)는 실행 중 허용이 필요한 권한
        val needed = mutableListOf<String>()
        if (granted(SPEED_PERMISSION)) vm.onSpeedPermissionGranted() else needed += SPEED_PERMISSION
        if (granted(Manifest.permission.RECORD_AUDIO)) vm.onMicPermissionGranted() else needed += Manifest.permission.RECORD_AUDIO
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) vm.onLocationPermissionGranted() else needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isNotEmpty()) askPermissions.launch(needed.toTypedArray())
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
    }
}
