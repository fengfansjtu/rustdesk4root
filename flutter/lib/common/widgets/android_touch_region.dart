import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';

class AndroidTouchRegion extends StatefulWidget {
  const AndroidTouchRegion({
    super.key,
    required this.canStart,
    required this.onDown,
    required this.onMove,
    required this.onUp,
    this.onScaleStart,
    required this.onScaleUpdate,
    required this.onScaleEnd,
    required this.child,
  });

  final bool Function(PointerDownEvent) canStart;
  final AsyncValueSetter<Offset> onDown;
  final AsyncValueSetter<Offset> onMove;
  final AsyncCallback onUp;
  final GestureScaleStartCallback? onScaleStart;
  final GestureScaleUpdateCallback onScaleUpdate;
  final GestureScaleEndCallback onScaleEnd;
  final Widget child;

  @override
  State<AndroidTouchRegion> createState() => _AndroidTouchRegionState();
}

class _AndroidTouchRegionState extends State<AndroidTouchRegion> {
  final _pointers = <int>{};
  int? _activePointer;
  final _pending = <({bool move, AsyncCallback send})>[];
  bool _sending = false;

  void _enqueue(AsyncCallback send, {bool move = false}) {
    final event = (move: move, send: send);
    if (move && _pending.isNotEmpty && _pending.last.move) {
      _pending[_pending.length - 1] = event;
    } else {
      _pending.add(event);
    }
    if (!_sending) unawaited(_flush());
  }

  Future<void> _flush() async {
    _sending = true;
    try {
      while (_pending.isNotEmpty) {
        final event = _pending.removeAt(0);
        try {
          await event.send();
        } catch (error, stack) {
          FlutterError.reportError(FlutterErrorDetails(
            exception: error,
            stack: stack,
            library: 'Android touch input',
          ));
        }
      }
    } finally {
      _sending = false;
    }
  }

  void _release() {
    if (_activePointer == null) return;
    _activePointer = null;
    _enqueue(widget.onUp);
  }

  void _down(PointerDownEvent event) {
    if (event.kind != PointerDeviceKind.touch &&
        event.kind != PointerDeviceKind.stylus &&
        event.kind != PointerDeviceKind.invertedStylus) return;
    _pointers.add(event.pointer);
    if (_pointers.length > 1) {
      _release();
    } else if (widget.canStart(event)) {
      _activePointer = event.pointer;
      final send = widget.onDown;
      _enqueue(() => send(event.localPosition));
    }
  }

  void _move(PointerMoveEvent event) {
    if (_activePointer == event.pointer) {
      final send = widget.onMove;
      _enqueue(() => send(event.localPosition), move: true);
    }
  }

  void _up(PointerUpEvent event) {
    if (_activePointer == event.pointer) {
      final send = widget.onMove;
      _enqueue(() => send(event.localPosition), move: true);
      _release();
    }
    _pointers.remove(event.pointer);
  }

  void _cancel(PointerCancelEvent event) {
    if (_activePointer == event.pointer) _release();
    _pointers.remove(event.pointer);
  }

  @override
  void dispose() {
    _release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Listener(
        onPointerDown: _down,
        onPointerMove: _move,
        onPointerUp: _up,
        onPointerCancel: _cancel,
        child: GestureDetector(
          onScaleStart: widget.onScaleStart,
          onScaleUpdate: (details) {
            if (details.pointerCount > 1) widget.onScaleUpdate(details);
          },
          onScaleEnd: widget.onScaleEnd,
          child: widget.child,
        ),
      );
}
