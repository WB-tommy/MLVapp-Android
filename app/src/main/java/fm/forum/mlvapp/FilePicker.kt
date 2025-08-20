package fm.forum.mlvapp

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fm.forum.mlvapp.data.MLVFileForList
import fm.forum.mlvapp.data.MLVIHeader
//
//@Composable
//fun FilePreviewCard(mlvFile: MLVFileForList) {
//    OutlinedCard(
//        colors = CardDefaults.cardColors(
//            containerColor = MaterialTheme.colorScheme.surface,
//        ),
//        border = BorderStroke(1.dp, Color.Black),
//        modifier = Modifier
//            .size(width = 240.dp, height = 100.dp)
//    ) {
//        Text(
//            text = "Outlined",
//            modifier = Modifier
//                .padding(16.dp),
//            textAlign = TextAlign.Center,
//        )
//    }
//}
//
//@Composable
//fun FileListView(mlvFileList: List<MLVFileForList>) {
//    LazyRow {
//        items(mlvFileList) { mlvFile ->
//            FilePreviewCard(mlvFile)
//        }
//    }
//}
