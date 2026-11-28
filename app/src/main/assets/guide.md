# Portal Muse

A 10 inch Android touchscreen, formerly a video calling device, now running a
personal, unofficial gadget client. It stays plugged in at home.

## What it does

It shows a home screen with clock, date and weather, or a web dashboard chosen
by its owner. People use it by touch. They can also talk to you from it. A quick
button on its home screen sends you a message and your reply appears as text on
its screen, where they can answer by voice or keyboard; keep those replies short
and readable from across a room. Or they tap the call button for a spoken
conversation: you are heard on its speaker, and the microphone is muted while
you speak. If its owner enables it, saying "Hey Muse" near it starts such a
call.

## Using the screen

Calls from here are announced as a wearable, yet this is a 10 inch screen. What
the person asks for here, show here with these commands, not on their phone.

When it lists `display.show_images` and `display.show_page`, the screen is yours
to illustrate what you say. Asked about a place, an animal, a recipe, a plan or
numbers, show pictures or a page without waiting to be asked, then say briefly
what is on screen. It also works during a call. Prefer a few good pictures with
short captions. For web pictures give direct image file links you have checked,
for example from Wikimedia Commons.

## Reaching people

When it lists these commands:

- `display.notify`: a short banner for what matters now (transit disruption,
  reminder, deadline today). `urgent` makes it red with a sound.
- `display.ask`: a question with approve and reject buttons; it waits for the
  tap. Use it when a task needs a decision right away. Anyone near the screen can
  tap, so still confirm on the phone before spending, sending to others,
  deleting or anything hard to undo. `no_answer` is not a yes.
- `home.set_shortcuts`: up to four home buttons that send you a message.
- `display.night_mode`: turn the screen light off now, or set daily hours, when
  asked. You stay connected.
- `frame.set_photos`: photos shown when the screen is idle. Use the person's own
  photos when they ask.

## Bluetooth

When it lists `ble.*` commands, you can reach nearby Bluetooth LE devices: scan,
connect, discover, read, write, subscribe. The first connection to a device asks
the owner on the screen. Write only what the person asked for, and say what you
did. It works while the Portal Muse screen is in front.

## Looking

When it lists `camera.capture`, you can ask to see something. The owner gets a
live view and decides whether to take and send one photo. Ask only when it helps
what they are doing.

## Feed

When it lists `feed.set`, the device has a "Fil" page for the suggestions you
curate for the person. Fill it when they ask, with images; if they want it kept
up to date, send it again when your feed changes.

## Timers, volume, apps

`timer.*` runs kitchen timers that ring on the device, `audio.volume` sets how
loud it is. During a call the device applies spoken volume requests itself and
hangs up. With `apps.*` you can write small touch apps and keep them on its
home screen; make them when asked. The guide result's `capabilities.board` gives
the screen size.

## What you can do

- `device.health` reads software version, uptime, memory, storage, network and
  screen state.
- `device.guide` returns this text.

## Limits

Apart from those screen and Bluetooth commands, you cannot control anything
through this device. The owner can turn them off. The microphone sends audio only after someone taps the microphone or
call button, or
says the wake word where it is enabled; you cannot start it. The camera gives only photos the owner sends. No speaker, shell or file access is exposed.

## Recovery

If it is offline, check power and Wi-Fi, then open the Portal Muse app on it.
Pairing again is done from its maintenance menu.
