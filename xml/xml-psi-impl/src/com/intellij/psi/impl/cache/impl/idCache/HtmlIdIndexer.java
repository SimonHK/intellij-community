// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl.cache.impl.idCache;

import com.intellij.lexer.HtmlHighlightingLexer;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.psi.impl.cache.impl.OccurrenceConsumer;
import com.intellij.psi.impl.cache.impl.id.LexerBasedIdIndexer;

public class HtmlIdIndexer extends LexerBasedIdIndexer {
  @Override
  public Lexer createLexer(final OccurrenceConsumer consumer) {
    return createIndexingLexer(consumer);
  }

  static XHtmlFilterLexer createIndexingLexer(OccurrenceConsumer consumer) {
    return new XHtmlFilterLexer(new HtmlHighlightingLexer(FileTypeManager.getInstance().getStdFileType("CSS")), consumer);
  }

  @Override
  public int getVersion() {
    return 2;
  }
}
