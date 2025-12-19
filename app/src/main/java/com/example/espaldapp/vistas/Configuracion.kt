package com.example.espaldapp.vistas

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import com.example.espaldapp.viewmodel.HomeViewModel
import com.example.espaldapp.viewmodel.HomeViewModelFactory
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfiguracionScreen(
    navController: NavController,
    parentBackStackEntry: NavBackStackEntry,
    viewModel: HomeViewModel = viewModel(
        viewModelStoreOwner = parentBackStackEntry,
        factory = HomeViewModelFactory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val backgroundColor = Color(0xFFCFE2F3)
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    
    var greenThreshold by remember { mutableStateOf((uiState.postureData?.umbralVerde ?: 80).toString()) }
    var redThreshold by remember { mutableStateOf((uiState.postureData?.umbralRojo ?: 120).toString()) }
    var timeThresholdSec by remember { mutableStateOf("30") }
    var alarmSwitch by remember { mutableStateOf(uiState.alarmEnabled) }
    
    // Observar mensajes de toast
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Configuración",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1976D2),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = backgroundColor
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Tarjeta de Umbrales de Distancia
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Título de la sección
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Straighten,
                            contentDescription = null,
                            tint = Color(0xFF1976D2),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Umbrales de distancia",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF424242)
                        )
                    }
                    
                    Divider()
                    
                    // Umbral verde (postura correcta)
                    OutlinedTextField(
                        value = greenThreshold,
                        onValueChange = { greenThreshold = it },
                        label = { Text("Distancia mínima (mm)") },
                        placeholder = { Text("80") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50)
                            )
                        },
                        supportingText = { 
                            Text(
                                "Distancia ideal - Postura correcta",
                                fontSize = 12.sp,
                                color = Color(0xFF424242)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF4CAF50),
                            focusedLabelColor = Color(0xFF4CAF50),
                            unfocusedTextColor = Color.Black,
                            focusedTextColor = Color.Black,
                            unfocusedLabelColor = Color(0xFF424242)
                        )
                    )
                    
                    // Umbral rojo (mala postura)
                    OutlinedTextField(
                        value = redThreshold,
                        onValueChange = { redThreshold = it },
                        label = { Text("Distancia máxima (mm)") },
                        placeholder = { Text("120") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color.Red
                            )
                        },
                        supportingText = { 
                            Text(
                                "Límite antes de alerta - Mala postura",
                                fontSize = 12.sp,
                                color = Color(0xFF424242)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Red,
                            focusedLabelColor = Color.Red,
                            unfocusedTextColor = Color.Black,
                            focusedTextColor = Color.Black,
                            unfocusedLabelColor = Color(0xFF424242)
                        )
                    )

                    // Tiempo de alerta (segundos)
                    OutlinedTextField(
                        value = timeThresholdSec,
                        onValueChange = { timeThresholdSec = it },
                        label = { Text("Tiempo para alerta (segundos)") },
                        placeholder = { Text("30") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = Color(0xFFFF9800)
                            )
                        },
                        supportingText = {
                            Text(
                                "Se activa alerta si la postura es mala por este tiempo",
                                fontSize = 12.sp,
                                color = Color(0xFF424242)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedTextColor = Color.Black,
                            focusedTextColor = Color.Black,
                            unfocusedLabelColor = Color(0xFF424242),
                            focusedLabelColor = Color(0xFFFF9800)
                        )
                    )
                    
                    // Botón para guardar umbrales
                    Button(
                        onClick = {
                            val green = greenThreshold.toIntOrNull()
                            val red = redThreshold.toIntOrNull()
                            val timeSec = timeThresholdSec.toIntOrNull()

                            // Validaciones de rango según firmware
                            val greenValid = green != null && green in 60..200
                            val redValid = red != null && red in 80..400
                            val timeValid = timeSec != null && timeSec in 5..300

                            if (!greenValid) {
                                Toast.makeText(context, "Umbral verde debe estar entre 60 y 200 mm", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.setGreenThreshold(green!!)
                            }

                            if (!redValid) {
                                Toast.makeText(context, "Umbral rojo debe estar entre 80 y 400 mm", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.setRedThreshold(red!!)
                            }

                            if (!timeValid) {
                                Toast.makeText(context, "Tiempo de alerta debe estar entre 5 y 300 segundos", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.setTimeThreshold(timeSec!!)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1976D2)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Guardar parámetros")
                    }
                }
            }
            
            // Tarjeta de Alertas
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Título de la sección
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = Color(0xFF1976D2),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Alertas",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF424242)
                        )
                    }
                    
                    Divider()
                    
                    // Switch de alerta sonora
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (alarmSwitch) {
                                    Icons.AutoMirrored.Filled.VolumeUp
                                } else {
                                    Icons.AutoMirrored.Filled.VolumeOff
                                },
                                contentDescription = null,
                                tint = if (alarmSwitch) Color(0xFF1976D2) else Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Alerta sonora",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.Black
                                )
                                Text(
                                    text = "Sonido después de 5 segundos en mala postura",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF424242),
                                    fontSize = 12.sp
                                )
                            }
                        }
                        Switch(
                            checked = alarmSwitch,
                            onCheckedChange = {
                                alarmSwitch = it
                                viewModel.toggleAlarm(it)
                            }
                        )
                    }
                    
                    Divider()
                    
                    // Información sobre alerta de 1 hora
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Alerta de descanso",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = Color.Black
                            )
                            Text(
                                text = "Se activará automáticamente después de 1 hora de sesión",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF424242),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
            
            // Información adicional
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFF1976D2)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Los cambios en los umbrales se enviarán al dispositivo Arduino cuando lo guardes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF424242)
                    )
                }
            }
        }
    }
}
