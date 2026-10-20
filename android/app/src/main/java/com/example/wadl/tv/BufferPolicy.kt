package com.example.wadl.tv

/**
 * سياسة الوسادة (منطق صرف، بلا اعتماد على أندرويد):
 *  PREBUFFER: لا نبدأ التشغيل قبل اكتمال الوسادة المختارة (3/5/10 دقائق)
 *  PLAYING  : تشغيل؛ إذا نفدت البيانات → REBUFFER
 *  REBUFFER : لا نستأنف قبل أن تعود وسادة كافية (نصف الهدف بين 60 و180 ثانية)
 * ودفاع أخير: إن هبطت الوسادة تحت 40 ثانية يبطّئ التشغيل قليلاً (0.92x) حتى تمتلئ، فلا يُلاحَظ تقريباً.
 */
class BufferPolicy(initialTargetSec: Int) {
    enum class Phase { PREBUFFER, PLAYING, REBUFFER }
    enum class Action { NONE, PLAY, PAUSE }

    var targetSec: Int = initialTargetSec.coerceAtLeast(30)
    var phase: Phase = Phase.PREBUFFER
        private set
    var stalls: Int = 0
        private set
    var speed: Float = 1f
        private set

    val resumeSec: Int get() = (targetSec / 2).coerceIn(60, 180)

    /** الهدف الذي يُقاس عليه الشريط الآن */
    val barTargetSec: Int get() = if (phase == Phase.REBUFFER) resumeSec else targetSec

    /** يُستدعى كل ثانية: aheadSec = ما هو جاهز للتشغيل، starved = المشغّل جاف فعلاً */
    fun onTick(aheadSec: Double, starved: Boolean): Action {
        when (phase) {
            Phase.PREBUFFER -> if (aheadSec >= targetSec) {
                phase = Phase.PLAYING
                speed = 1f
                return Action.PLAY
            }
            Phase.PLAYING -> {
                if (starved && aheadSec < 2.0) {
                    phase = Phase.REBUFFER
                    stalls++
                    speed = 1f
                    return Action.PAUSE
                }
                speed = when {
                    speed == 1f && aheadSec < LOW_WATER -> SLOW
                    speed != 1f && aheadSec > RECOVER -> 1f
                    else -> speed
                }
            }
            Phase.REBUFFER -> if (aheadSec >= resumeSec) {
                phase = Phase.PLAYING
                return Action.PLAY
            }
        }
        return Action.NONE
    }

    /** بعد توقفين فأكثر نقترح وسادة أكبر للمشاهدة التالية (بالدقائق) أو null */
    fun suggestedNextMin(): Int? {
        if (stalls < 2) return null
        return when {
            targetSec < 300 -> 5
            targetSec < 600 -> 10
            else -> null
        }
    }

    companion object {
        const val LOW_WATER = 40.0
        const val RECOVER = 70.0
        const val SLOW = 0.92f
    }
}
