import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { HomeService } from '../core/services/home.service';

type BackendStatus = 'checking' | 'up' | 'down';

@Component({
  selector: 'app-home',
  imports: [RouterLink, MatButtonModule, MatCardModule, MatIconModule],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent implements OnInit {
  private readonly homeService = inject(HomeService);

  readonly status = signal<BackendStatus>('checking');
  readonly detail = signal<string>('');

  ngOnInit(): void {
    this.homeService.hello().subscribe({
      next: (res) => {
        this.status.set('up');
        this.detail.set(`${res.service} — ${res.status}`);
      },
      error: () => {
        this.status.set('down');
        this.detail.set('Backend injoignable');
      },
    });
  }
}
