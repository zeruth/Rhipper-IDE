// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.product

import com.intellij.platform.ide.customization.ExternalProductResourceUrls
import com.intellij.platform.ide.customization.FeedbackReporter
import com.intellij.util.Url
import com.intellij.util.Urls
import java.net.URLEncoder

private const val IDE_REPOSITORY = "https://github.com/zeruth/Rhipper-IDE"
private const val RHIPPER_REPOSITORY = "https://github.com/zeruth/Rhipper"

/**
 * The web pages of the Rhipper workbench: its GitHub repositories.
 *
 * The workbench does not check for its own updates yet, so [updateMetadataUrl] is `null`.
 */
internal class RhipperExternalProductResourceUrls : ExternalProductResourceUrls {
  override val updateMetadataUrl: Url? = null

  override val bugReportUrl: ((description: String) -> Url) = { description ->
    Urls.newFromEncoded("$IDE_REPOSITORY/issues/new?body=" + URLEncoder.encode(description, Charsets.UTF_8))
  }

  override val technicalSupportUrl: ((description: String) -> Url)? = null

  override val feedbackReporter: FeedbackReporter? = null

  override val downloadPageUrl: Url = Urls.newFromEncoded("$IDE_REPOSITORY/releases")

  override val youTubeChannelUrl: Url? = null

  override val keyboardShortcutsPdfUrl: Url? = null

  override val whatIsNewPageUrl: Url? = null

  override val gettingStartedPageUrl: Url = Urls.newFromEncoded("$RHIPPER_REPOSITORY#readme")

  override val helpPageUrl: ((topicId: String) -> Url)? = null
}
