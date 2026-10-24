package com.example.kurdishtv.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kurdishtv.ui.motion.CornerScale
import com.example.ui.theme.LocalAppColors

@Composable
fun SearchBarM3(
    query: String,
    onQueryChange: (String) -> Unit,
    /**
     * Reports whether the field holds focus, so the screen can stop the remote's
     * number pad from stealing digits from someone typing a search.
     */
    onFocusChanged: (Boolean) -> Unit = {},
    /**
     * Whether the field may take focus at all.
     *
     * False for the first frame of a visit, on purpose. A text field is the one
     * focus target on the screen whose focus has a side effect beyond its own
     * highlight: on Android TV it also *opens the on-screen keyboard*. If the
     * focus system lands on it during launch — before the grid has had a frame
     * to claim focus for the first card — the viewer watches the keyboard rise
     * over the channel grid for no reason they asked for, and digits typed for a
     * channel number go into the search field instead.
     *
     * The window is one frame, so no human can lose access to it: the D-pad
     * cannot move faster than the frame that lifts the gate, and the field is a
     * normal, reachable search box from then on. See `MainTvScreen`, which owns
     * the gate.
     */
    focusEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val focusManager = LocalFocusManager.current

    // A fixed pill. It used to square off from 26dp to 18dp as the field took focus,
    // on a spring. A search box that changes outline is easier to notice on a
    // television than one that only recolours its border — and it still is, because
    // the border colour changes with it. The outline itself no longer has to move to
    // make the same point.
    //
    // 26dp rather than 28dp: a Material single-line text field is 56dp, so 28dp puts
    // the four corner arcs exactly on the centre line and renders the pill slightly
    // pinched at all four corners.
    val focused = remember { mutableStateOf(false) }
    val fieldShape = CornerScale.uniform(26.dp).toShape()

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                text = "Search Kurdish channels…",
                color = colors.textTertiary,
                fontSize = 14.sp
            )
        },
        leadingIcon = {
            SvgIcon(
                resId = KurdishTvIcons.Search,
                contentDescription = "Search channels",
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    SvgIcon(
                        resId = KurdishTvIcons.Close,
                        contentDescription = "Clear search",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        singleLine = true,
        shape = fieldShape,
        // The keyboard's own Search/Enter key. Without an IME action the field gets
        // the default one, which on most IMEs is a bare newline that does nothing —
        // so the one control on the screen that submits a search had no way to be
        // submitted. `Done` is also what dismisses the keyboard, which is the
        // behaviour a viewer expects from a single-line field once they have seen
        // the results narrow down.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            // Drop the focus, which is what closes the IME. Setting the local
            // `focused` flag alone would square the field off while the keyboard
            // stayed on screen — the exact opposite of what was asked for.
            onSearch = { focusManager.clearFocus() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            // One step above a card. The field is a control that sits *on* the
            // header, and at the card's own level it read as a card-shaped hole in
            // the bar rather than as something you type into.
            focusedContainerColor = colors.surfaceHigh,
            unfocusedContainerColor = colors.surfaceHigh,
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.border,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = colors.primary
        ),
        modifier = modifier
            .testTag("search_input")
            .focusProperties { canFocus = focusEnabled }
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            // Clear focus when the keyboard's Search key is pressed. Handled through
            // the same state the shape animation reads, so the field visibly squares
            // off again as the keyboard leaves rather than staying focused behind it.
            .onFocusChanged {
                focused.value = it.isFocused
                onFocusChanged(it.isFocused)
            }
    )
}
