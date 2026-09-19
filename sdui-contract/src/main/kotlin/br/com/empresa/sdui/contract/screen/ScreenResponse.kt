package br.com.empresa.sdui.contract.screen

data class ScreenResponse(
    val envelope: ScreenEnvelope,
    val skeleton: SkeletonResponse,
    val sections: List<SectionResponse>,
)
