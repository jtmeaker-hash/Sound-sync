import re

with open('app/src/main/java/com/example/ui/MainDjScreen.kt', 'r') as f:
    content = f.read()

# 1. Remove val analysisProgressPercent by viewModel.analysisProgressPercent.collectAsState() from MainDjScreen top level
content = re.sub(r'val analysisProgressPercent by viewModel\.analysisProgressPercent\.collectAsState\(\)\n\s+', '', content)

# 2. In caller: analysisProgressPercent = analysisProgressPercent -> analysisProgressFlow = viewModel.analysisProgressPercent
content = re.sub(r'analysisProgressPercent = analysisProgressPercent,', 'analysisProgressFlow = viewModel.analysisProgressPercent,', content)

# 3. In PositionAwareSpectrogramTab signature: analysisProgressPercent: Int -> analysisProgressFlow: kotlinx.coroutines.flow.StateFlow<Int>
content = re.sub(r'analysisProgressPercent: Int,', 'analysisProgressFlow: kotlinx.coroutines.flow.StateFlow<Int>,', content)

# 4. In PositionAwareSpectrogramTab body:
# add val analysisProgressPercent by analysisProgressFlow.collectAsState()
body_replacement = r"""{
    val currentPositionSec = audioEngine.currentPositionSec.collectAsState().value
    val playbackProgress = audioEngine.playbackProgress.collectAsState().value
    val analysisProgressPercent by analysisProgressFlow.collectAsState()
"""
content = re.sub(r'\{\n\s+val currentPositionSec = audioEngine\.currentPositionSec\.collectAsState\(\)\.value\n\s+val playbackProgress = audioEngine\.playbackProgress\.collectAsState\(\)\.value\n', body_replacement, content)

with open('app/src/main/java/com/example/ui/MainDjScreen.kt', 'w') as f:
    f.write(content)

