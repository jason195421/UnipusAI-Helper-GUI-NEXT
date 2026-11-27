"""Small Selenium-compatible primitives for the Android WebView driver.

Only DOM automation is implemented. Browser-owned permission prompts, trusted
hardware input and the desktop WebDriver wire protocol are intentionally absent.
"""
from __future__ import annotations

import time


class WebDriverException(Exception):
    def __init__(self, msg=None, screen=None, stacktrace=None):
        super().__init__(msg or "Android WebView operation failed")
        self.msg, self.screen, self.stacktrace = msg, screen, stacktrace


class NoSuchElementException(WebDriverException):
    pass


class StaleElementReferenceException(WebDriverException):
    pass


class TimeoutException(WebDriverException):
    pass


class InvalidSessionIdException(WebDriverException):
    pass


class NoSuchDriverException(WebDriverException):
    pass


class SessionNotCreatedException(WebDriverException):
    pass


class JavascriptException(WebDriverException):
    pass


class NoSuchFrameException(WebDriverException):
    pass


class NoSuchWindowException(WebDriverException):
    pass


class InvalidSelectorException(WebDriverException):
    pass


class InvalidArgumentException(WebDriverException):
    pass


class ElementNotInteractableException(WebDriverException):
    pass


class ElementClickInterceptedException(WebDriverException):
    pass


class NoAlertPresentException(WebDriverException):
    pass


class UnsupportedCommandException(WebDriverException):
    pass


class By:
    ID = "id"
    XPATH = "xpath"
    LINK_TEXT = "link text"
    PARTIAL_LINK_TEXT = "partial link text"
    NAME = "name"
    TAG_NAME = "tag name"
    CLASS_NAME = "class name"
    CSS_SELECTOR = "css selector"


class Keys:
    NULL = "\ue000"
    BACKSPACE = "\ue003"
    BACK_SPACE = BACKSPACE
    TAB = "\ue004"
    CLEAR = "\ue005"
    RETURN = "\ue006"
    ENTER = "\ue007"
    SHIFT = "\ue008"
    CONTROL = "\ue009"
    CTRL = CONTROL
    ALT = "\ue00a"
    PAUSE = "\ue00b"
    ESCAPE = "\ue00c"
    SPACE = "\ue00d"
    END = "\ue010"
    HOME = "\ue011"
    ARROW_LEFT = "\ue012"
    ARROW_UP = "\ue013"
    ARROW_RIGHT = "\ue014"
    ARROW_DOWN = "\ue015"
    DELETE = "\ue017"
    META = "\ue03d"
    COMMAND = META


class WebDriverWait:
    def __init__(self, driver, timeout, poll_frequency=0.5, ignored_exceptions=None):
        self._driver = driver
        self._timeout = max(0.0, float(timeout))
        self._poll = float(poll_frequency) if poll_frequency else 0.5
        if self._poll <= 0:
            raise ValueError("poll_frequency must be positive")
        ignored = (NoSuchElementException,)
        if ignored_exceptions:
            ignored += ((ignored_exceptions,) if isinstance(ignored_exceptions, type)
                        else tuple(ignored_exceptions))
        self._ignored = ignored

    def until(self, method, message=""):
        deadline = time.monotonic() + self._timeout
        last = None
        while True:
            try:
                value = method(self._driver)
                if value:
                    return value
            except self._ignored as exc:
                last = exc
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutException(message or "Timed out waiting for the WebView condition") from last
            time.sleep(min(self._poll, remaining))

    def until_not(self, method, message=""):
        deadline = time.monotonic() + self._timeout
        while True:
            try:
                value = method(self._driver)
                if not value:
                    return value
            except self._ignored:
                return True
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutException(message or "Timed out waiting for the condition to become false")
            time.sleep(min(self._poll, remaining))


class EC:
    @staticmethod
    def presence_of_element_located(locator):
        return lambda driver: driver.find_element(*locator)

    @staticmethod
    def presence_of_all_elements_located(locator):
        return lambda driver: driver.find_elements(*locator)

    @staticmethod
    def visibility_of(element):
        def predicate(_):
            try:
                return element if element.is_displayed() else False
            except StaleElementReferenceException:
                return False
        return predicate

    @staticmethod
    def visibility_of_element_located(locator):
        def predicate(driver):
            try:
                element = driver.find_element(*locator)
                return element if element.is_displayed() else False
            except (NoSuchElementException, StaleElementReferenceException):
                return False
        return predicate

    @staticmethod
    def element_to_be_clickable(mark):
        def predicate(driver):
            try:
                element = driver.find_element(*mark) if isinstance(mark, (tuple, list)) else mark
                return element if element.is_displayed() and element.is_enabled() else False
            except (NoSuchElementException, StaleElementReferenceException):
                return False
        return predicate

    @staticmethod
    def invisibility_of_element_located(locator):
        def predicate(driver):
            try:
                return not driver.find_element(*locator).is_displayed()
            except (NoSuchElementException, StaleElementReferenceException):
                return True
        return predicate

    @staticmethod
    def staleness_of(element):
        def predicate(_):
            try:
                element.is_enabled()
                return False
            except StaleElementReferenceException:
                return True
        return predicate

    @staticmethod
    def frame_to_be_available_and_switch_to_it(locator):
        def predicate(driver):
            try:
                frame = driver.find_element(*locator) if isinstance(locator, (tuple, list)) else locator
                driver.switch_to.frame(frame)
                return True
            except (NoSuchElementException, NoSuchFrameException):
                return False
        return predicate


_POINTER_JS = r"""
const el = arguments[0], kind = arguments[1];
if (!el || !el.isConnected) { const e = new Error('Element is detached'); e.name = 'StaleElementReferenceException'; throw e; }
el.scrollIntoView({block:'nearest', inline:'nearest'});
const r = el.getBoundingClientRect();
const opts = {bubbles:true, cancelable:true, composed:true, view:window,
    clientX:r.left+r.width/2, clientY:r.top+r.height/2, button:0,
    buttons:kind==='hold' || (kind==='move' && !!window.__unipusAndroidDrag) ? 1 : 0};
const mouse = name => el.dispatchEvent(new MouseEvent(name, opts));
const pointer = name => { if (typeof PointerEvent !== 'undefined') el.dispatchEvent(new PointerEvent(name, {...opts, pointerId:1, pointerType:'mouse', isPrimary:true})); };
if (kind === 'hold') {
    if (typeof DataTransfer === 'undefined' || typeof DragEvent === 'undefined') {
        const e = new Error('This WebView does not expose HTML drag events'); e.name = 'UnsupportedCommandException'; throw e;
    }
    window.__unipusAndroidDrag = {source:el, data:new DataTransfer()};
    pointer('pointerdown'); mouse('mousedown');
    el.dispatchEvent(new DragEvent('dragstart', {...opts, dataTransfer:window.__unipusAndroidDrag.data}));
} else if (kind === 'move') {
    pointer('pointermove'); mouse('mouseover'); mouse('mousemove');
    const drag = window.__unipusAndroidDrag;
    if (drag) {
        el.dispatchEvent(new DragEvent('dragenter', {...opts, dataTransfer:drag.data}));
        el.dispatchEvent(new DragEvent('dragover', {...opts, dataTransfer:drag.data}));
    }
} else if (kind === 'release') {
    const drag = window.__unipusAndroidDrag;
    if (drag) {
        el.dispatchEvent(new DragEvent('drop', {...opts, dataTransfer:drag.data}));
        drag.source.dispatchEvent(new DragEvent('dragend', {...opts, dataTransfer:drag.data}));
        delete window.__unipusAndroidDrag;
    }
    pointer('pointerup'); mouse('mouseup');
} else if (kind === 'cancel') {
    pointer('pointercancel'); mouse('mouseup');
    delete window.__unipusAndroidDrag;
}
return true;
"""


class ActionChains:
    """DOM event chains; deliberately does not claim trusted OS pointer input."""
    def __init__(self, driver, duration=250):
        self._driver = driver
        self._actions = []
        self._target = None

    def move_to_element(self, to_element):
        self._actions.append(("move", to_element))
        return self

    def click(self, on_element=None):
        self._actions.append(("click", on_element))
        return self

    def click_and_hold(self, on_element=None):
        self._actions.append(("hold", on_element))
        return self

    def release(self, on_element=None):
        self._actions.append(("release", on_element))
        return self

    def pause(self, seconds):
        seconds = float(seconds)
        if seconds < 0:
            raise ValueError("pause must not be negative")
        self._actions.append(("pause", seconds))
        return self

    def send_keys(self, *keys_to_send):
        self._actions.append(("keys", keys_to_send))
        return self

    def send_keys_to_element(self, element, *keys_to_send):
        return self.click(element).send_keys(*keys_to_send)

    def drag_and_drop(self, source, target):
        return self.click_and_hold(source).move_to_element(target).release(target)

    def reset_actions(self):
        self._actions.clear()
        if self._target is not None:
            self._driver.execute_script(_POINTER_JS, self._target, "cancel")
        self._target = None

    def perform(self):
        pending, self._actions = self._actions, []
        for kind, value in pending:
            if kind == "pause":
                time.sleep(value)
                continue
            if kind == "keys":
                (self._target or self._driver.switch_to.active_element).send_keys(*value)
                continue
            target = value or self._target or self._driver.switch_to.active_element
            self._target = target
            if kind == "click":
                target.click()
            else:
                self._driver.execute_script(_POINTER_JS, target, kind)
        return None


expected_conditions = EC
