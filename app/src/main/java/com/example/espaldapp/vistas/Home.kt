package com.example.espaldapp.vistas

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.font.FontStyle
import com.example.espaldapp.bluetooth.ConnectionState
import com.example.espaldapp.model.EstadoPostura
import com.example.espaldapp.viewmodel.HomeViewModel
import com.example.espaldapp.viewmodel.HomeViewModelFactory
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.flow.collectLatest
import androidx.navigation.NavBackStackEntry

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
    viewModel: HomeViewModel = viewModel(
        viewModelStoreOwner = backStackEntry,
        factory = HomeViewModelFactory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val backgroundColor = Color(0xFFCFE2F3)
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    // Permisos de Bluetooth según versión de Android
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
    
    val permissionsState = rememberMultiplePermissionsState(bluetoothPermissions)
    
    // Observar mensajes de toast
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    // Solicitar permisos al iniciar
    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // Barra superior con botones
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Botón Bluetooth (conectar/desconectar)
                IconButton(
                    onClick = {
                        if (permissionsState.allPermissionsGranted) {
                            if (uiState.connectionState == ConnectionState.CONNECTED) {
                                viewModel.disconnect()
                            } else {
                                viewModel.connectToHC06()
                            }
                        } else {
                            permissionsState.launchMultiplePermissionRequest()
                        }
                    }
                ) {
                    Icon(
                        imageVector = when (uiState.connectionState) {
                            ConnectionState.CONNECTED -> Icons.Default.Bluetooth
                            ConnectionState.CONNECTING -> Icons.AutoMirrored.Filled.BluetoothSearching
                            ConnectionState.DISCONNECTED -> Icons.Default.BluetoothDisabled
                        },
                        contentDescription = "Estado Bluetooth",
                        tint = when (uiState.connectionState) {
                            ConnectionState.CONNECTED -> Color.Blue
                            ConnectionState.CONNECTING -> Color.Gray
                            ConnectionState.DISCONNECTED -> Color.Red
                        }
                    )
                }

                Text(
                    text = "EspaldApp",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.Black,
                        fontStyle = FontStyle.Italic,
                        fontSize = 30.sp
                    )
                )

                // Botón de configuración
                IconButton(
                    onClick = { navController.navigate("configuracion") }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configuración",
                        tint = Color.Black
                    )
                }
            }

            // Contenido principal
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Estado de conexión
                if (uiState.connectionState != ConnectionState.CONNECTED) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFE0B2)
                        )
                    ) {
                        Text(
                            text = if (uiState.connectionState == ConnectionState.CONNECTING) {
                                "Conectando..."
                            } else {
                                "Desconectado - Toca el icono Bluetooth para conectar"
                            },
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Indicador de estado (icono)
                val estadoPostura = uiState.postureData?.estadoPostura() ?: EstadoPostura.CORRECTA
                when (estadoPostura) {
                    EstadoPostura.ALERTA -> {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Alerta de mala postura",
                            tint = Color.Red,
                            modifier = Modifier
                                .size(60.dp)
                                .padding(bottom = 8.dp)
                        )
                    }
                    EstadoPostura.MALA -> {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Mala postura",
                            tint = Color(0xFFFF6D00),
                            modifier = Modifier
                                .size(60.dp)
                                .padding(bottom = 8.dp)
                        )
                    }
                    EstadoPostura.ADVERTENCIA -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Advertencia",
                            tint = Color(0xFFFFB300),
                            modifier = Modifier
                                .size(60.dp)
                                .padding(bottom = 8.dp)
                        )
                    }
                    EstadoPostura.CORRECTA -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Postura correcta",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier
                                .size(60.dp)
                                .padding(bottom = 8.dp)
                        )
                    }
                }

                // Distancia actual
                val distanciaCm = uiState.postureData?.distanciaCm() ?: 0
                Text(
                    text = if (distanciaCm > 0) "$distanciaCm cms" else "-- cms",
                    fontSize = 50.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Distancia actual",
                    fontSize = 20.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(40.dp))

                // Tiempo de sesión
                Text(
                    text = uiState.seatedTime,
                    fontSize = 50.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tiempo de sesión",
                    fontSize = 20.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color.Black
                )

                // Botones de acción: Finalizar Sesión y Ver Historial
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botón Reiniciar Sesión
                    IconButton(
                        onClick = {
                            if (uiState.seatedTime != "00:00:00") {
                                viewModel.reiniciarSesion()
                            }
                        },
                        enabled = uiState.seatedTime != "00:00:00"
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reiniciar sesión",
                            tint = if (uiState.seatedTime != "00:00:00") Color.Black else Color.Gray,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    // Botón Ver Historial
                    IconButton(
                        onClick = {
                            navController.navigate("historial")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Ver historial",
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Información adicional
                if (uiState.postureData != null) {
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InfoCard(
                            label = "Estado",
                            value = when (estadoPostura) {
                                EstadoPostura.CORRECTA -> "Correcta"
                                EstadoPostura.ADVERTENCIA -> "Advertencia"
                                EstadoPostura.MALA -> "Incorrecta"
                                EstadoPostura.ALERTA -> "Alerta"
                            },
                            color = when (estadoPostura) {
                                EstadoPostura.CORRECTA -> Color(0xFF4CAF50)
                                EstadoPostura.ADVERTENCIA -> Color(0xFFFFB300)
                                EstadoPostura.MALA, EstadoPostura.ALERTA -> Color.Red
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun InfoCard(
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = Modifier
            .width(150.dp)
            .padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = Color.Gray,
                fontStyle = FontStyle.Italic
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

