# SoundPower reaches hidden framework members (android.media.audiofx.AudioEffect) by reflection;
# those are not part of the app, so R8 leaves them alone. No app classes are used reflectively.
