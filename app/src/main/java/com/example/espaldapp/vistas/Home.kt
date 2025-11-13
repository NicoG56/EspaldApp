package com.example.espaldapp.vistas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.font.FontStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

@Composable
fun HomeScreen(navController: NavController) {
    val backgroundColor = Color(0xFFCFE2F3)

    var distancia by remember { mutableIntStateOf(5) }
    var horaActual by remember { mutableStateOf("") }

    val formatoHora = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        while (true) {
            distancia = Random.nextInt(4, 10) // Entre 5 y 8 cm
            delay(1000L)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            horaActual = formatoHora.format(Date())
            delay(1000L)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { /* Por realizar: abrir configuración */ }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configurar parámetros de medición",
                        tint = Color.Black
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

                IconButton(onClick = { /* Por realizar: activar/desactivar alerta sonora */ }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Activar/Desactivar alerta sonora",
                        tint = Color.Black
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                if (distancia > 6) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Alerta de mala postura",
                        tint = Color.Red,
                        modifier = Modifier
                            .size(60.dp)
                            .padding(bottom = 8.dp)
                    )
                }

                Text(
                    text = "$distancia cms",
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

                Text(
                    text = horaActual.ifEmpty { "--:--:--" },
                    fontSize = 50.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sesión actual",
                    fontSize = 20.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color.Black
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
