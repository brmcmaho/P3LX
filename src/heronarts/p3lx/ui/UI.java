/**
 * Copyright 2013- Mark C. Slee, Heron Arts LLC
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 * ##library.name##
 * ##library.sentence##
 * ##library.url##
 *
 * @author      ##author##
 * @modified    ##date##
 * @version     ##library.prettyVersion## (##library.version##)
 */

package heronarts.p3lx.ui;

import heronarts.lx.LX;
import heronarts.lx.LXBus;
import heronarts.lx.LXChannel;
import heronarts.lx.LXEngine;
import heronarts.lx.LXLoopTask;
import heronarts.lx.LXMappingEngine;
import heronarts.lx.parameter.LXParameter;
import heronarts.lx.parameter.LXParameterListener;
import heronarts.lx.parameter.StringParameter;
import heronarts.p3lx.P3LX;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PFont;
import processing.event.Event;
import processing.event.KeyEvent;
import processing.event.MouseEvent;

/**
 * Top-level container for all overlay UI elements.
 */
public class UI implements LXEngine.Dispatch {

  private static UI instance = null;

  private class UIRoot extends UIObject implements UIContainer {

    private UIRoot() {
      this.ui = UI.this;
    }

    @Override
    protected void onKeyPressed(KeyEvent keyEvent, char keyChar, int keyCode) {
      if (topLevelKeyEventHandler != null) {
        topLevelKeyEventHandler.onKeyPressed(keyEvent, keyChar, keyCode);
      }
      if (!keyEventConsumed()) {
        if (keyCode == java.awt.event.KeyEvent.VK_TAB) {
          if (keyEvent.isShiftDown()) {
            focusPrev();
          } else {
            focusNext();
          }
        }
      }
    }

    @Override
    protected void onKeyReleased(KeyEvent keyEvent, char keyChar, int keyCode) {
      if (topLevelKeyEventHandler != null) {
        topLevelKeyEventHandler.onKeyReleased(keyEvent, keyChar, keyCode);
      }
    }

    @Override
    protected void onKeyTyped(KeyEvent keyEvent, char keyChar, int keyCode) {
      if (topLevelKeyEventHandler != null) {
        topLevelKeyEventHandler.onKeyTyped(keyEvent, keyChar, keyCode);
      }
    }

    private void redraw() {
      for (UIObject child : this.children) {
        if (child instanceof UI2dComponent) {
          ((UI2dComponent) child).redraw();
        }
      }
    }

    private UIObject findCurrentFocus() {
      UIObject currentFocus = this;
      while (currentFocus.focusedChild != null) {
        currentFocus = currentFocus.focusedChild;
      }
      return currentFocus;
    }

    private UIObject findNextFocusable() {
      // Identify the deepest focused object
      UIObject focus = findCurrentFocus();

      // Check if it has a child that is eligible for focus
      UIObject focusableChild = findNextFocusableChild(focus, 0);
      if (focusableChild != null) {
        return focusableChild;
      }

      // Work up the tree, trying siblings at each level
      while (focus.parent != null) {
        int focusIndex = focus.parent.children.indexOf(focus);
        focusableChild = findNextFocusableChild(focus.parent, focusIndex + 1);
        if (focusableChild != null) {
          return focusableChild;
        }
        focus = focus.parent;
      }

      // We ran out! Loop around from the front...
      return findNextFocusableChild(this, 0);
    }

    private UIObject findNextFocusableChild(UIObject focus, int startIndex) {
      for (int i = startIndex; i < focus.children.size(); ++i) {
        UIObject child = focus.children.get(i);
        if (child.isVisible()) {
          if (child instanceof UITabFocus) {
            return child;
          }
          UIObject recurse = findNextFocusableChild(child, 0);
          if (recurse != null) {
            return recurse;
          }
        }
      }
      return null;
    }

    private UIObject findPrevFocusable() {
      // Identify the deepest focused object
      UIObject focus = findCurrentFocus();

      // Check its previous siblings, depth-first
      while (focus.parent != null) {
        int focusIndex = focus.parent.children.indexOf(focus);
        UIObject focusableChild = findPrevFocusableChild(focus.parent, focusIndex - 1);
        if (focusableChild != null) {
          return focusableChild;
        }
        if (focus.parent instanceof UITabFocus) {
          return focus.parent;
        }
        focus = focus.parent;
      }

      // We failed! Wrap around to the end
      return findPrevFocusableChild(this, this.children.size() - 1);
    }

    private UIObject findPrevFocusableChild(UIObject focus, int startIndex) {
      for (int i = startIndex; i >= 0; --i) {
        UIObject child = focus.children.get(i);
        if (child.isVisible()) {
          UIObject recurse = findPrevFocusableChild(child, child.children.size() - 1);
          if (recurse != null) {
            return recurse;
          }
          if (child instanceof UITabFocus) {
            return child;
          }
        }
      }
      return null;
    }

    @Override
    public UIObject getContentTarget() {
      return this;
    }

    @Override
    public float getContentWidth() {
      return this.ui.width;
    }

    @Override
    public float getContentHeight() {
      return this.ui.height;
    }
  }

  /**
   * Redraw may be called from any thread
   */
  private final List<UI2dComponent> threadSafeRedrawList =
    Collections.synchronizedList(new ArrayList<UI2dComponent>());

  /**
   * Objects to redraw on current pass thru animation thread
   */
  private final List<UI2dComponent> uiThreadRedrawList =
    new ArrayList<UI2dComponent>();

  /**
   * Input events coming from the event thread
   */
  private final List<Event> threadSafeInputEventQueue =
    Collections.synchronizedList(new ArrayList<Event>());

  /**
   * Events on the local processing thread
   */
  private final List<Event> engineThreadInputEvents = new ArrayList<Event>();

  public class Timer {
    public long drawNanos = 0;
  }

  public final Timer timer = new Timer();

  final P3LX lx;

  public final PApplet applet;

  private UIRoot root;

  public final StringParameter contextualHelpText = new StringParameter("Contextual Help");

  private boolean hasBackground = false;

  private int backgroundColor = UI.BLACK;

  private static final long INIT_RUN = -1;
  private long lastMillis = INIT_RUN;

  private UIEventHandler topLevelKeyEventHandler = null;

  /**
   * UI look and feel
   */
  public final UITheme theme;

  /**
   * White color
   */
  public final static int WHITE = 0xffffffff;

  /**
   * Black color
   */
  public final static int BLACK = 0xff000000;

  /**
   * Width of the UI
   */
  int width;

  /**
   * Height of the UI
   */
  int height;

  private boolean resizable = false;

  boolean midiMapping = false;
  boolean modulationSourceMapping = false;
  boolean modulationTargetMapping = false;

  private UIControlTarget controlTarget = null;
  private UIModulationSource modulationSource = null;

  public UI(P3LX lx) {
    this(lx.applet, lx);
  }

  /**
   * Creates a new UI instance
   *
   * @param applet The PApplet
   */
  public UI(PApplet applet) {
    this(applet, null);
  }

  private UI(PApplet applet, final P3LX lx) {
    this.lx = lx;
    this.applet = applet;
    this.width = lx.applet.width;
    this.height = lx.applet.height;
    this.theme = new UITheme(applet);
    LX.initTimer.log("P3LX: UI: Theme");
    this.root = new UIRoot();
    LX.initTimer.log("P3LX: UI: Root");
    applet.registerMethod("draw", this);
    applet.registerMethod("keyEvent", this);
    applet.registerMethod("mouseEvent", this);
    LX.initTimer.log("P3LX: UI: register");
    if (lx != null) {
      lx.engine.setInputDispatch(this);
    }
    lx.engine.mapping.mode.addListener(new LXParameterListener() {
      public void onParameterChanged(LXParameter p) {
        midiMapping = lx.engine.mapping.getMode() == LXMappingEngine.Mode.MIDI;
        modulationSourceMapping = lx.engine.mapping.getMode() == LXMappingEngine.Mode.MODULATION_SOURCE;
        modulationTargetMapping = lx.engine.mapping.getMode() == LXMappingEngine.Mode.MODULATION_TARGET;
        root.redraw();
      }
    });
    UI.instance = this;
  }

  public static UI get() {
    return UI.instance;
  }

  public UI setBackground(boolean hasBackground) {
    this.hasBackground = hasBackground;
    return this;
  }

  public UI setBackgroundColor(int backgroundColor) {
    this.hasBackground = true;
    this.backgroundColor = backgroundColor;
    return this;
  }

  public void focusPrev() {
    UIObject focusTarget = this.root.findPrevFocusable();
    if (focusTarget != null) {
      focusTarget.focus();
    }
  }

  public void focusNext() {
    UIObject focusTarget = this.root.findNextFocusable();
    if (focusTarget != null) {
      focusTarget.focus();
    }
  }

  /**
   * Sets an object to handle top-level input events
   *
   * @param eventHandler
   * @return
   */
  public UI setTopLevelKeyEventHandler(UIEventHandler eventHandler) {
    this.topLevelKeyEventHandler = eventHandler;
    return this;
  }

  UI setControlTarget(UIControlTarget controlTarget) {
    this.lx.engine.mapping.setControlTarget(controlTarget.getControlTarget());
    if (this.controlTarget != controlTarget) {
      if (this.controlTarget != null) {
        ((UI2dComponent) this.controlTarget).redraw();
      }
      this.controlTarget = controlTarget;
      if (this.controlTarget != null) {
        ((UI2dComponent) this.controlTarget).redraw();
      }
    }
    return this;
  }

  UIControlTarget getControlTarget() {
    return this.controlTarget;
  }

  public UI mapModulationSource(UIModulationSource modulationSource) {
    this.modulationSource = modulationSource;
    this.lx.engine.mapping.setMode(modulationSource == null ? LXMappingEngine.Mode.OFF : LXMappingEngine.Mode.MODULATION_TARGET);
    return this;
  }

  UIModulationSource getModulationSource() {
    return this.modulationSource;
  }

  /**
   * Sets whether the UI should be resizable.
   *
   * @param resizable
   * @return
   */
  public UI setResizable(boolean resizable) {
    this.applet.getSurface().setResizable(this.resizable = resizable);
    return this;
  }

  /**
   * Get width of the UI
   *
   * @return width
   */
  public int getWidth() {
    return this.width;
  }

  /**
   * Get height of the UI
   *
   * @return height
   */
  public int getHeight() {
    return this.height;
  }

  /**
   * Add a task to be performed on every loop of the UI engine.
   *
   * @param loopTask
   * @return
   */
  public UI addLoopTask(LXLoopTask loopTask) {
    this.root.addLoopTask(loopTask);
    return this;
  }

  /**
   * Remove a task from the UI engine
   *
   * @param loopTask
   * @return
   */
  public UI removeLoopTask(LXLoopTask loopTask) {
    this.root.removeLoopTask(loopTask);
    return this;
  }

  /**
   * Add a 2d context to this UI
   *
   * @param layer UI layer
   * @return this
   */
  public UI addLayer(UI2dContext layer) {
    layer.addToContainer(this.root);
    return this;
  }

  /**
   * Remove a 2d context from this UI
   *
   * @param layer UI layer
   * @return this UI
   */
  public UI removeLayer(UI2dContext layer) {
    layer.removeFromContainer();
    return this;
  }

  /**
   * Add a 3d context to this UI
   *
   * @param layer 3d context
   * @return this UI
   */
  public UI addLayer(UI3dContext layer) {
    addLoopTask(layer);
    this.root.children.add(layer);
    layer.parent = this.root;
    layer.setUI(this);
    return this;
  }

  public UI removeLayer(UI3dContext layer) {
    if (layer.parent != this.root) {
      throw new IllegalStateException("Cannot remove 3d layer which is not present");
    }
    this.root.children.remove(layer);
    layer.parent = null;
    return this;
  }

  /**
   * Brings a layer to the top of the UI stack
   *
   * @param layer UI layer
   * @return this UI
   */
  public UI bringToTop(UI2dContext layer) {
    this.root.children.remove(layer);
    this.root.children.add(layer);
    return this;
  }

  /**
   * Load a font file
   *
   * @param font Font name
   * @return PFont object
   */
  public PFont loadFont(String font) {
    return this.applet.loadFont(font);
  }

  void redraw(UI2dComponent object) {
    this.threadSafeRedrawList.add(object);
  }

  /**
   * Draws the UI
   */
  public final void draw() {
    // Check for a resize event
    if (this.resizable) {
      if (this.applet.width != width || this.applet.height != height) {
        this.width = this.applet.width;
        this.height = this.applet.height;
        this.root.resize(this);
      }
    }

    beforeDraw();

    long drawStart = System.nanoTime();

    long nowMillis = System.currentTimeMillis();
    if (this.lastMillis == INIT_RUN) {
      // Initial frame is arbitrarily 16 milliseconds (~60 fps)
      this.lastMillis = nowMillis - 16;
    }
    double deltaMs = nowMillis - this.lastMillis;
    this.lastMillis = nowMillis;

    // Run loop tasks through the UI tree
    this.root.loop(deltaMs);

    // Iterate through all objects that need redraw state marked
    this.uiThreadRedrawList.clear();
    synchronized (this.threadSafeRedrawList) {
      this.uiThreadRedrawList.addAll(this.threadSafeRedrawList);
      this.threadSafeRedrawList.clear();
    }
    for (UI2dComponent object : this.uiThreadRedrawList) {
      object._redraw();
    }

    // Draw from the root
    if (this.hasBackground) {
      this.applet.background(this.backgroundColor);
    }
    this.root.draw(this, this.applet.g);

    this.timer.drawNanos = System.nanoTime() - drawStart;
  }

  // Subclasses may override...
  protected void beforeDraw() {
  }

  private boolean isThreaded() {
    return (this.lx != null) && (this.lx.engine.isThreaded());
  }


  public void dispatch() {
    // This is invoked on the LXEngine thread, which may be different
    // from the Processing Animation thread. Events are always
    // processed on the engine thread to avoid bugs.
    engineThreadInputEvents.clear();
    synchronized (threadSafeInputEventQueue) {
      engineThreadInputEvents.addAll(threadSafeInputEventQueue);
      threadSafeInputEventQueue.clear();
    }
    for (Event event : engineThreadInputEvents) {
      if (event instanceof KeyEvent) {
        _keyEvent((KeyEvent) event);
      } else if (event instanceof MouseEvent) {
        _mouseEvent((MouseEvent) event);
      }
    }
  }

  public void mouseEvent(MouseEvent mouseEvent) {
    // NOTE: this method is invoked from the Processing thread! The LX engine
    // may be running on a separate thread.
    if (isThreaded()) {
      // NOTE: it's okay that no lock is held here, if threading mode changes
      // right here, the event queue will still be picked up by next iteration
      // of the EngineUILoopTask
      this.threadSafeInputEventQueue.add(mouseEvent);
    } else {
      // NOTE: also okay to be lock-free here, if threading mode was off then
      // there is no other thread that would have made a call to start the
      // threading engine
      _mouseEvent(mouseEvent);
    }
  }

  private float pmx, pmy;

  private void _mouseEvent(MouseEvent mouseEvent) {
    switch (mouseEvent.getAction()) {
    case MouseEvent.WHEEL:
      this.root.mouseWheel(mouseEvent, mouseEvent.getX(), mouseEvent.getY(), mouseEvent.getCount());
      return;
    case MouseEvent.PRESS:
      this.pmx = mouseEvent.getX();
      this.pmy = mouseEvent.getY();
      this.root.mousePressed(mouseEvent, this.pmx, this.pmy);
      break;
    case processing.event.MouseEvent.RELEASE:
      this.root.mouseReleased(mouseEvent, mouseEvent.getX(), mouseEvent.getY());
      break;
    case processing.event.MouseEvent.CLICK:
      this.root.mouseClicked(mouseEvent, mouseEvent.getX(), mouseEvent.getY());
      break;
    case processing.event.MouseEvent.DRAG:
      float mx = mouseEvent.getX();
      float my = mouseEvent.getY();
      this.root.mouseDragged(mouseEvent, mx, my, mx - this.pmx, my - this.pmy);
      this.pmx = mx;
      this.pmy = my;
      break;
    case processing.event.MouseEvent.MOVE:
      this.root.mouseMoved(mouseEvent, mouseEvent.getX(), mouseEvent.getY());
      break;
    }
  }

  public void keyEvent(KeyEvent keyEvent) {
    // Do not close on the ESC key, P3LX UI uses it
    if (this.applet.key == PConstants.ESC) {
      this.applet.key = 0;
    }

    // Default handler for key events on the UI thread
    _uiThreadDefaultKeyEvent(keyEvent);

    // NOTE: this method is invoked from the Processing thread! The LX engine
    // may be running on a separate thread.
    if (isThreaded()) {
      this.threadSafeInputEventQueue.add(keyEvent);
    } else {
      _keyEvent(keyEvent);
    }
  }

  private void _keyEvent(KeyEvent keyEvent) {
    _engineThreadDefaultKeyEvent(keyEvent);

    char keyChar = keyEvent.getKey();
    int keyCode = keyEvent.getKeyCode();
    switch (keyEvent.getAction()) {
    case KeyEvent.RELEASE:
      this.root.keyReleased(keyEvent, keyChar, keyCode);
      break;
    case KeyEvent.PRESS:
      this.root.keyPressed(keyEvent, keyChar, keyCode);
      break;
    case KeyEvent.TYPE:
      this.root.keyTyped(keyEvent, keyChar, keyCode);
      break;
    default:
      throw new RuntimeException("Invalid keyEvent type: " + keyEvent.getAction());
    }
  }

  private void _uiThreadDefaultKeyEvent(KeyEvent keyEvent) {
    char keyChar = keyEvent.getKey();
    int action = keyEvent.getAction();
    if (action == KeyEvent.RELEASE) {
      switch (Character.toLowerCase(keyChar)) {
      case 'f':
        this.lx.flags.showFramerate = false;
        break;
      }
    } else if (action == KeyEvent.PRESS) {
      switch (keyChar) {
      case 'f':
        if (keyEvent.isControlDown() || keyEvent.isMetaDown()) {
          this.lx.flags.showFramerate = true;
        }
        break;
      }
    }
  }

  public final void onSaveAs(final File file) {
    if (file != null) {
      this.lx.engine.addTask(new Runnable() {
        public void run() {
          lx.saveProject(file);
        }
      });
    }
  }

  public final void onLoad(final File file) {
    if (file != null) {
      this.lx.engine.addTask(new Runnable() {
        public void run() {
          lx.loadProject(file);
        }
      });
    }
  }

  private void _engineThreadDefaultKeyEvent(KeyEvent keyEvent) {
    char keyChar = keyEvent.getKey();
    int keyCode = keyEvent.getKeyCode();
    int action = keyEvent.getAction();
    if (action == KeyEvent.RELEASE) {
      switch (Character.toLowerCase(keyChar)) {
      case 's':
        if (keyEvent.isControlDown() || keyEvent.isMetaDown()) {
          if (keyEvent.isShiftDown()) {
            this.applet.selectOutput("Select a file to save:", "onSaveAs", this.applet.saveFile("Project.lxp"), this);
          } else {
            lx.saveProject();
          }
        }
        break;
      case 'o':
        if (keyEvent.isControlDown() || keyEvent.isMetaDown()) {
          this.applet.selectInput("Select a file to load:", "onLoad", this.applet.saveFile("default.lxp"), this);
        }
        break;
      case '[':
      case ']':
        LXBus bus = this.lx.engine.getFocusedChannel();
        if (bus instanceof LXChannel) {
          if (keyChar == '[') {
            ((LXChannel) bus).goPrev();
          } else {
            ((LXChannel) bus).goNext();
          }
        }
        break;
      case ' ':
        if (this.lx.flags.keyboardTempo) {
          this.lx.tempo.tap();
        }
        break;
      }
    } else if (action == KeyEvent.PRESS) {
      if (keyCode == java.awt.event.KeyEvent.VK_T && (keyEvent.isMetaDown() || keyEvent.isControlDown())) {
        this.lx.engine.setThreaded(!this.lx.engine.isThreaded());
      }
      switch (keyCode) {
      case java.awt.event.KeyEvent.VK_LEFT:
        if (this.lx.flags.keyboardTempo) {
          this.lx.tempo.setBpm(this.lx.tempo.bpm() - .1);
        }
        break;
      case java.awt.event.KeyEvent.VK_RIGHT:
        if (this.lx.flags.keyboardTempo) {
          this.lx.tempo.setBpm(this.lx.tempo.bpm() + .1);
        }
        break;
      }
    }
  }
}
