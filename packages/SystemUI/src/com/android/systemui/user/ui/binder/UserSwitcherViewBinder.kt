/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.user.ui.binder

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.helper.widget.Flow as FlowWidget
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.Gefingerpoken
import com.android.systemui.R
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.user.UserSwitcherPopupMenu
import com.android.systemui.user.UserSwitcherRootView
import com.android.systemui.user.ui.viewmodel.UserActionViewModel
import com.android.systemui.user.ui.viewmodel.UserSwitcherViewModel
import com.android.systemui.util.children
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Binds a user switcher to its view-model. */
object UserSwitcherViewBinder {

    private const val USER_VIEW_TAG = "user_view"

    /** Binds the given view to the given view-model. */
    fun bind(
        view: ViewGroup,
        viewModel: UserSwitcherViewModel,
        lifecycleOwner: LifecycleOwner,
        layoutInflater: LayoutInflater,
        falsingCollector: FalsingCollector,
        onFinish: () -> Unit,
    ) {
        val rootView: UserSwitcherRootView = view.requireViewById(R.id.user_switcher_root)
        val flowWidget: FlowWidget = view.requireViewById(R.id.flow)
        val addButton: View = view.requireViewById(R.id.add)
        val cancelButton: View = view.requireViewById(R.id.cancel)
        val popupMenuAdapter = MenuAdapter(layoutInflater)
        var popupMenu: UserSwitcherPopupMenu? = null

        rootView.touchHandler =
            object : Gefingerpoken {
                override fun onTouchEvent(ev: MotionEvent?): Boolean {
                    falsingCollector.onTouchEvent(ev)
                    return false
                }
            }
        addButton.setOnClickListener { viewModel.onOpenMenuButtonClicked() }
        cancelButton.setOnClickListener { viewModel.onCancelButtonClicked() }

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
                launch {
                    viewModel.isFinishRequested
                        .filter { it }
                        .collect {
                            onFinish()
                            viewModel.onFinished()
                        }
                }
            }
        }

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.isOpenMenuButtonVisible.collect { addButton.isVisible = it } }

                launch {
                    viewModel.isMenuVisible.collect { isVisible ->
                        if (isVisible && popupMenu?.isShowing != true) {
                            popupMenu?.dismiss()
                            // Use post to make sure we show the popup menu *after* the activity is
                            // ready to show one to avoid a WindowManager$BadTokenException.
                            view.post {
                                popupMenu =
                                    createAndShowPopupMenu(
                                        context = view.context,
                                        anchorView = addButton,
                                        adapter = popupMenuAdapter,
                                        onDismissed = viewModel::onMenuClosed,
                                    )
                            }
                        } else if (!isVisible && popupMenu?.isShowing == true) {
                            popupMenu?.dismiss()
                            popupMenu = null
                        }
                    }
                }

                launch {
                    viewModel.menu.collect { menuViewModels ->
                        popupMenuAdapter.setItems(menuViewModels)
                    }
                }

                launch {
                    viewModel.maximumUserColumns.collect { maximumColumns ->
                        flowWidget.setMaxElementsWrap(maximumColumns)
                    }
                }

                launch {
                    viewModel.users.collect { users ->
                        val viewPool =
                            view.children.filter { it.tag == USER_VIEW_TAG }.toMutableList()
                        viewPool.forEach {
                            view.removeView(it)
                            flowWidget.removeView(it)
                        }
                        users.forEach { userViewModel ->
                            val userView =
                                if (viewPool.isNotEmpty()) {
                                    viewPool.removeAt(0)
                                } else {
                                    val inflatedView =
                                        layoutInflater.inflate(
                                            R.layout.user_switcher_fullscreen_item,
                                            view,
                                            false,
                                        )
                                    inflatedView.tag = USER_VIEW_TAG
                                    inflatedView
                                }
                            userView.id = View.generateViewId()
                            view.addView(userView)
                            flowWidget.addView(userView)
                            UserViewBinder.bind(
                                view = userView,
                                viewModel = userViewModel,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun createAndShowPopupMenu(
        context: Context,
        anchorView: View,
        adapter: MenuAdapter,
        onDismissed: () -> Unit,
    ): UserSwitcherPopupMenu {
        return UserSwitcherPopupMenu(context).apply {
            this.anchorView = anchorView
            setAdapter(adapter)
            setOnDismissListener { onDismissed() }
            setOnItemClickListener { _, _, position, _ ->
                val itemPositionExcludingHeader = position - 1
                adapter.getItem(itemPositionExcludingHeader).onClicked()
                dismiss()
            }

            show()
        }
    }

    /** Adapter for the menu that can be opened. */
    private class MenuAdapter(
        private val layoutInflater: LayoutInflater,
    ) : BaseAdapter() {

        private val items = mutableListOf<UserActionViewModel>()

        override fun getCount(): Int {
            return items.size
        }

        override fun getItem(position: Int): UserActionViewModel {
            return items[position]
        }

        override fun getItemId(position: Int): Long {
            return getItem(position).viewKey
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view =
                convertView
                    ?: layoutInflater.inflate(
                        R.layout.user_switcher_fullscreen_popup_item,
                        parent,
                        false
                    )
            val viewModel = getItem(position)
            view.requireViewById<ImageView>(R.id.icon).setImageResource(viewModel.iconResourceId)
            view.requireViewById<TextView>(R.id.text).text =
                view.resources.getString(viewModel.textResourceId)
            return view
        }

        fun setItems(items: List<UserActionViewModel>) {
            this.items.clear()
            this.items.addAll(items)
            notifyDataSetChanged()
        }
    }
}
