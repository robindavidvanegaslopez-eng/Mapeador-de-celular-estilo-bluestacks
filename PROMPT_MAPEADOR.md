# Prompt de desarrollo: Mapeador de Toques (Android nativo)

## Rol
Eres un ingeniero Android senior (Kotlin, AccessibilityService, WindowManager, GestureDescription)
con experiencia en mapeadores de teclas estilo BlueStacks. Responde y comenta el código en español.

## Contexto
Existe un proyecto Kotlin (`com.touchmap`, minSdk 26, sin dependencias externas, compilable con
GitHub Actions `assembleDebug`). Ya incluye: servicio de accesibilidad, burbuja flotante, modo
edición con arrastre, botones Toque / Mantener / Turbo / Deslizar / Joystick / Macro, perfiles,
portrait y landscape independientes, pausa automática al aparecer un cuadro de texto, teclado
externo y autodetección del joystick por captura de pantalla. Trabaja sobre esa base sin reescribirla.

## Objetivo de esta iteración: mecánica del joystick
El usuario usa juegos con **joystick fijo** (no flotante), donde la distancia del dedo al centro
determina la velocidad. Se necesita:

1. **Presión continua y quieta.** Al activar una dirección, el dedo virtual se apoya **directamente**
   en un punto de la zona del joystick (la última zona colocada o autodetectada) y se mantiene
   presionado sin parar. No se hace un arrastre desde el centro.
2. **Punto por dirección.** Punto = centro + dirección × radio × distancia, con distancia ajustable
   (por defecto 100 % = borde, velocidad máxima).
3. **8 direcciones.** Arriba, abajo, izquierda, derecha y las 4 diagonales al combinar dos teclas
   (W+A, W+D, S+A, S+D) o al empujar el pad táctil. Las diagonales se normalizan para quedar sobre
   el mismo círculo. Teclas opuestas a la vez se cancelan.
4. **Cambio de dirección sin soltar.** El mismo trazo continuo se mueve al nuevo punto con un salto
   muy corto, para que el personaje no se frene ni se detenga entre direcciones.
5. **Soltar.** Al soltar todas las teclas (o el pad) el dedo se levanta.
6. **Compatibilidad.** Conservar un interruptor «modo clásico» (arrastre desde el centro) por
   joystick, y que los perfiles ya guardados sigan cargando.

## Requisitos transversales
- Los gestos del joystick no deben cancelarse al disparar otros botones a la vez.
- Se pausa con el teclado de Android o un campo de texto y se reanuda al salir.
- Sin fugas de ventanas ni de manejadores al rotar, pausar o detener el servicio.
- Interfaz en español, coherente con el estilo oscuro actual.

## Criterios de aceptación
- El proyecto compila sin errores ni advertencias nuevas.
- Mantener W produce un toque quieto en el punto superior; añadir A lo mueve a la diagonal
  superior izquierda sin levantar el dedo; soltar todo lo levanta.
- El ajuste de distancia (20–100 %) mueve el punto de apoyo de forma visible.
- Un perfil guardado antes del cambio sigue funcionando.

## Entrega
Código completo de los archivos modificados, ZIP del proyecto (mismo nombre que la versión
anterior) y un resumen breve de los cambios.
